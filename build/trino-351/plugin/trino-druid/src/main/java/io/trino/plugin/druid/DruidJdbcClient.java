/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.druid;

import com.google.common.collect.ImmutableList;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcOutputTableHandle;
import io.trino.plugin.jdbc.JdbcSplit;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.WriteFunction;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.StandardColumnMappings.defaultVarcharColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharColumnMapping;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;

public class DruidJdbcClient
        extends BaseJdbcClient
{
    // Druid maintains its datasources related metadata by setting the catalog name as "druid"
    // Note that while a user may name the catalog name as something else, metadata queries made
    // to druid will always have the TABLE_CATALOG set to DRUID_CATALOG
    private static final String DRUID_CATALOG = "druid";
    // All the datasources in Druid are created under schema "druid"
    public static final String DRUID_SCHEMA = "druid";

    @Inject
    public DruidJdbcClient(BaseJdbcConfig config, ConnectionFactory connectionFactory)
    {
        super(config, "\"", connectionFactory);
    }

    @Override
    protected Collection<String> listSchemas(Connection connection)
    {
        return ImmutableList.of(DRUID_SCHEMA);
    }

    //Overridden to filter out tables that don't match schemaTableName
    @Override
    public Optional<JdbcTableHandle> getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        try (Connection connection = connectionFactory.openConnection(session)) {
            String jdbcSchemaName = schemaTableName.getSchemaName();
            String jdbcTableName = schemaTableName.getTableName();
            try (ResultSet resultSet = getTables(connection, Optional.of(jdbcSchemaName), Optional.of(jdbcTableName))) {
                List<JdbcTableHandle> tableHandles = new ArrayList<>();
                while (resultSet.next()) {
                    tableHandles.add(new JdbcTableHandle(
                            schemaTableName,
                            DRUID_CATALOG,
                            resultSet.getString("TABLE_SCHEM"),
                            resultSet.getString("TABLE_NAME")));
                }
                if (tableHandles.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(
                        getOnlyElement(
                                tableHandles
                                        .stream()
                                        .filter(
                                                jdbcTableHandle ->
                                                        Objects.equals(jdbcTableHandle.getSchemaName(), schemaTableName.getSchemaName())
                                                                && Objects.equals(jdbcTableHandle.getTableName(), schemaTableName.getTableName()))
                                        .collect(Collectors.toList())));
            }
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    /*
     * Overridden since the {@link BaseJdbcClient#getTables(Connection, Optional, Optional)}
     * method uses character escaping that doesn't work well with Druid's Avatica handler.
     * Unfortunately, because we can't escape search characters like '_' and '%", this call
     * ends up retrieving metadata for all tables that match the search
     * pattern. For ex - LIKE some_table matches somertable, somextable and some_table.
     *
     * See getTableHandle(JdbcIdentity, SchemaTableName)} to look at
     * how tables are filtered.
     */
    @Override
    protected ResultSet getTables(Connection connection, Optional<String> schemaName, Optional<String> tableName)
            throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        return metadata.getTables(DRUID_CATALOG,
                DRUID_SCHEMA,
                tableName.orElse(null),
                null);
    }

    @Override
    public Optional<ColumnMapping> toTrinoType(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        switch (typeHandle.getJdbcType()) {
            case Types.VARCHAR:
                int columnSize = typeHandle.getRequiredColumnSize();
                if (columnSize == -1) {
                    return Optional.of(varcharColumnMapping(createUnboundedVarcharType()));
                }
                return Optional.of(defaultVarcharColumnMapping(columnSize));
        }
        // TODO implement proper type mapping
        return legacyToPrestoType(session, connection, typeHandle);
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        // TODO implement proper type mapping
        return legacyToWriteMapping(session, type);
    }

    // Druid doesn't like table names to be qualified with catalog names in the SQL query.
    // Hence, overriding this method to pass catalog as null.
    @Override
    public PreparedStatement buildSql(ConnectorSession session, Connection connection, JdbcSplit split, JdbcTableHandle table, List<JdbcColumnHandle> columns)
            throws SQLException
    {
        String schemaName = table.getSchemaName();
        checkArgument("druid".equals(schemaName), "Only \"druid\" schema is supported");
        return new QueryBuilder(this)
                .buildSql(
                        session,
                        connection,
                        new RemoteTableName(Optional.empty(), table.getRemoteTableName().getSchemaName(), table.getRemoteTableName().getTableName()),
                        table.getGroupingSets(),
                        columns,
                        table.getConstraint(),
                        split.getAdditionalPredicate(),
                        tryApplyLimit(table.getLimit()));
    }

    /*
     * Overridden since the {@link BaseJdbcClient#getColumns(JdbcTableHandle, DatabaseMetaData)}
     * method uses character escaping that doesn't work well with Druid's Avatica handler.
     * Unfortunately, because we can't escape search characters like '_' and '%",
     * this call ends up retrieving columns for all tables that match the search
     * pattern. For ex - LIKE some_table matches somertable, somextable and some_table.
     *
     * See getColumns(ConnectorSession, JdbcTableHandle)} to look at tables are filtered.
     */
    @Override
    protected ResultSet getColumns(JdbcTableHandle tableHandle, DatabaseMetaData metadata)
            throws SQLException
    {
        return metadata.getColumns(
                tableHandle.getCatalogName(),
                tableHandle.getSchemaName(),
                tableHandle.getTableName(),
                null);
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public JdbcOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public JdbcOutputTableHandle beginInsertTable(ConnectorSession session, JdbcTableHandle tableHandle, List<JdbcColumnHandle> columns)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DML_NOT_SUPPORTED, "DML operations are not supported in the Trino Druid connector");
    }

    @Override
    public void commitCreateTable(ConnectorSession session, JdbcOutputTableHandle handle)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public void renameColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public void dropColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public void dropTable(ConnectorSession session, JdbcTableHandle handle)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public void rollbackCreateTable(ConnectorSession session, JdbcOutputTableHandle handle)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public String buildInsertSql(JdbcOutputTableHandle handle, List<WriteFunction> columnWriters)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DML_NOT_SUPPORTED, "DML operations are not supported in the Trino Druid connector");
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(DruidErrorCode.DRUID_DDL_NOT_SUPPORTED, "DDL operations are not supported in the Trino Druid connector");
    }
}
