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
package io.trino.plugin.jdbc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.AggregationApplicationResult;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.testing.TestingConnectorSession;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.jdbc.TestingJdbcTypeHandle.JDBC_BIGINT;
import static io.trino.plugin.jdbc.TestingJdbcTypeHandle.JDBC_VARCHAR;
import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static io.trino.spi.StandardErrorCode.PERMISSION_DENIED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestJdbcMetadata
{
    private TestingDatabase database;
    private JdbcMetadata metadata;
    private JdbcTableHandle tableHandle;

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        database = new TestingDatabase();
        metadata = new JdbcMetadata(new GroupingSetsEnabledJdbcClient(database.getJdbcClient()), false);
        tableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        database.close();
    }

    @Test
    public void testListSchemaNames()
    {
        assertTrue(metadata.listSchemaNames(SESSION).containsAll(ImmutableSet.of("example", "tpch")));
    }

    @Test
    public void testGetTableHandle()
    {
        JdbcTableHandle tableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers"));
        assertEquals(metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers")), tableHandle);
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("example", "unknown")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "numbers")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "unknown")));
    }

    @Test
    public void testGetColumnHandles()
    {
        // known table
        assertEquals(metadata.getColumnHandles(SESSION, tableHandle), ImmutableMap.of(
                "text", new JdbcColumnHandle("TEXT", JDBC_VARCHAR, VARCHAR),
                "text_short", new JdbcColumnHandle("TEXT_SHORT", JDBC_VARCHAR, createVarcharType(32)),
                "value", new JdbcColumnHandle("VALUE", JDBC_BIGINT, BIGINT)));

        // unknown table
        unknownTableColumnHandle(new JdbcTableHandle(new SchemaTableName("unknown", "unknown"), "unknown", "unknown", "unknown"));
        unknownTableColumnHandle(new JdbcTableHandle(new SchemaTableName("example", "numbers"), null, "example", "unknown"));
    }

    private void unknownTableColumnHandle(JdbcTableHandle tableHandle)
    {
        try {
            metadata.getColumnHandles(SESSION, tableHandle);
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException ignored) {
        }
    }

    @Test
    public void getTableMetadata()
    {
        // known table
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(SESSION, tableHandle);
        assertEquals(tableMetadata.getTable(), new SchemaTableName("example", "numbers"));
        assertEquals(tableMetadata.getColumns(), ImmutableList.of(
                new ColumnMetadata("text", VARCHAR, false, null, null, false, emptyMap()), // primary key is not null in H2
                new ColumnMetadata("text_short", createVarcharType(32)),
                new ColumnMetadata("value", BIGINT)));

        // escaping name patterns
        JdbcTableHandle specialTableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("exa_ple", "num_ers"));
        ConnectorTableMetadata specialTableMetadata = metadata.getTableMetadata(SESSION, specialTableHandle);
        assertEquals(specialTableMetadata.getTable(), new SchemaTableName("exa_ple", "num_ers"));
        assertEquals(specialTableMetadata.getColumns(), ImmutableList.of(
                new ColumnMetadata("te_t", VARCHAR, false, null, null, false, emptyMap()), // primary key is not null in H2
                new ColumnMetadata("va%ue", BIGINT)));

        // unknown tables should produce null
        unknownTableMetadata(new JdbcTableHandle(new SchemaTableName("u", "numbers"), null, "unknown", "unknown"));
        unknownTableMetadata(new JdbcTableHandle(new SchemaTableName("example", "numbers"), null, "example", "unknown"));
        unknownTableMetadata(new JdbcTableHandle(new SchemaTableName("example", "numbers"), null, "unknown", "numbers"));
    }

    private void unknownTableMetadata(JdbcTableHandle tableHandle)
    {
        try {
            metadata.getTableMetadata(SESSION, tableHandle);
            fail("Expected getTableMetadata of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException ignored) {
        }
    }

    @Test
    public void testListTables()
    {
        // all schemas
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.empty())), ImmutableSet.of(
                new SchemaTableName("example", "numbers"),
                new SchemaTableName("example", "view_source"),
                new SchemaTableName("example", "view"),
                new SchemaTableName("tpch", "orders"),
                new SchemaTableName("tpch", "lineitem"),
                new SchemaTableName("exa_ple", "table_with_float_col"),
                new SchemaTableName("exa_ple", "num_ers")));

        // specific schema
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("example"))), ImmutableSet.of(
                new SchemaTableName("example", "numbers"),
                new SchemaTableName("example", "view_source"),
                new SchemaTableName("example", "view")));
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("tpch"))), ImmutableSet.of(
                new SchemaTableName("tpch", "orders"),
                new SchemaTableName("tpch", "lineitem")));
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("exa_ple"))), ImmutableSet.of(
                new SchemaTableName("exa_ple", "num_ers"),
                new SchemaTableName("exa_ple", "table_with_float_col")));

        // unknown schema
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("unknown"))), ImmutableSet.of());
    }

    @Test
    public void getColumnMetadata()
    {
        assertEquals(
                metadata.getColumnMetadata(SESSION, tableHandle, new JdbcColumnHandle("text", JDBC_VARCHAR, VARCHAR)),
                new ColumnMetadata("text", VARCHAR));
    }

    @Test
    public void testCreateAndAlterTable()
    {
        SchemaTableName table = new SchemaTableName("example", "foo");
        metadata.createTable(SESSION, new ConnectorTableMetadata(table, ImmutableList.of(new ColumnMetadata("text", VARCHAR))), false);

        JdbcTableHandle handle = metadata.getTableHandle(SESSION, table);

        ConnectorTableMetadata layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getTable(), table);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), new ColumnMetadata("text", VARCHAR));

        metadata.addColumn(SESSION, handle, new ColumnMetadata("x", VARCHAR));
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getColumns().size(), 2);
        assertEquals(layout.getColumns().get(0), new ColumnMetadata("text", VARCHAR));
        assertEquals(layout.getColumns().get(1), new ColumnMetadata("x", VARCHAR));

        JdbcColumnHandle columnHandle = new JdbcColumnHandle("x", JDBC_VARCHAR, VARCHAR);
        metadata.dropColumn(SESSION, handle, columnHandle);
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), new ColumnMetadata("text", VARCHAR));

        SchemaTableName newTableName = new SchemaTableName("example", "bar");
        metadata.renameTable(SESSION, handle, newTableName);
        handle = metadata.getTableHandle(SESSION, newTableName);
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getTable(), newTableName);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), new ColumnMetadata("text", VARCHAR));
    }

    @Test
    public void testDropTableTable()
    {
        try {
            metadata.dropTable(SESSION, tableHandle);
            fail("expected exception");
        }
        catch (TrinoException e) {
            assertEquals(e.getErrorCode(), PERMISSION_DENIED.toErrorCode());
        }

        metadata = new JdbcMetadata(database.getJdbcClient(), true);
        metadata.dropTable(SESSION, tableHandle);

        try {
            metadata.getTableMetadata(SESSION, tableHandle);
            fail("expected exception");
        }
        catch (TrinoException e) {
            assertEquals(e.getErrorCode(), NOT_FOUND.toErrorCode());
        }
    }

    @Test
    public void testApplyFilterAfterAggregationPushdown()
    {
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(new JdbcMetadataSessionProperties(new JdbcMetadataConfig().setAggregationPushdownEnabled(true), Optional.empty()).getSessionProperties())
                .build();
        ColumnHandle groupByColumn = metadata.getColumnHandles(session, tableHandle).get("text");
        ConnectorTableHandle baseTableHandle = metadata.getTableHandle(session, new SchemaTableName("example", "numbers"));
        ConnectorTableHandle aggregatedTable = applyCountAggregation(session, baseTableHandle, ImmutableList.of(ImmutableList.of(groupByColumn)));

        Domain domain = Domain.singleValue(VARCHAR, utf8Slice("one"));
        JdbcTableHandle tableHandleWithFilter = applyConstraint(session, aggregatedTable, new Constraint(TupleDomain.withColumnDomains(ImmutableMap.of(groupByColumn, domain))));

        assertEquals(tableHandleWithFilter.getConstraint().getDomains(), Optional.of(ImmutableMap.of(groupByColumn, domain)));
    }

    @Test
    public void testCombineFiltersWithAggregationPushdown()
    {
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(new JdbcMetadataSessionProperties(new JdbcMetadataConfig().setAggregationPushdownEnabled(true), Optional.empty()).getSessionProperties())
                .build();
        ColumnHandle groupByColumn = metadata.getColumnHandles(session, tableHandle).get("text");
        ConnectorTableHandle baseTableHandle = metadata.getTableHandle(session, new SchemaTableName("example", "numbers"));

        Domain firstDomain = Domain.multipleValues(VARCHAR, ImmutableList.of(utf8Slice("one"), utf8Slice("two")));
        JdbcTableHandle filterResult = applyConstraint(session, baseTableHandle, new Constraint(TupleDomain.withColumnDomains(ImmutableMap.of(groupByColumn, firstDomain))));

        ConnectorTableHandle aggregatedTable = applyCountAggregation(session, filterResult, ImmutableList.of(ImmutableList.of(groupByColumn)));

        Domain secondDomain = Domain.multipleValues(VARCHAR, ImmutableList.of(utf8Slice("one"), utf8Slice("three")));
        JdbcTableHandle tableHandleWithFilter = applyConstraint(session, aggregatedTable, new Constraint(TupleDomain.withColumnDomains(ImmutableMap.of(groupByColumn, secondDomain))));
        assertEquals(
                tableHandleWithFilter.getConstraint().getDomains(),
                Optional.of(ImmutableMap.of(groupByColumn, Domain.singleValue(VARCHAR, utf8Slice("one")))));
    }

    @Test
    public void testNonGroupKeyPredicatePushdown()
    {
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(new JdbcMetadataSessionProperties(new JdbcMetadataConfig().setAggregationPushdownEnabled(true), Optional.empty()).getSessionProperties())
                .build();
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        ColumnHandle groupByColumn = columnHandles.get("text");
        ColumnHandle nonGroupByColumn = columnHandles.get("value");

        ConnectorTableHandle baseTableHandle = metadata.getTableHandle(session, new SchemaTableName("example", "numbers"));
        ConnectorTableHandle aggregatedTable = applyCountAggregation(session, baseTableHandle, ImmutableList.of(ImmutableList.of(groupByColumn)));

        Domain domain = Domain.singleValue(BIGINT, 123L);
        Optional<ConstraintApplicationResult<ConnectorTableHandle>> filterResult = metadata.applyFilter(
                session,
                aggregatedTable,
                new Constraint(TupleDomain.withColumnDomains(ImmutableMap.of(nonGroupByColumn, domain))));
        assertThat(filterResult).isEmpty();
    }

    @Test
    public void tesMultiGroupKeyPredicatePushdown()
    {
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(new JdbcMetadataSessionProperties(new JdbcMetadataConfig().setAggregationPushdownEnabled(true), Optional.empty()).getSessionProperties())
                .build();
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        ColumnHandle textColumn = columnHandles.get("text");
        ColumnHandle valueColumn = columnHandles.get("value");

        ConnectorTableHandle baseTableHandle = metadata.getTableHandle(session, new SchemaTableName("example", "numbers"));

        ConnectorTableHandle aggregatedTable = applyCountAggregation(session, baseTableHandle, ImmutableList.of(ImmutableList.of(textColumn, valueColumn), ImmutableList.of(textColumn)));

        Domain domain = Domain.singleValue(BIGINT, 123L);
        Optional<ConstraintApplicationResult<ConnectorTableHandle>> filterResult = metadata.applyFilter(
                session,
                aggregatedTable,
                new Constraint(TupleDomain.withColumnDomains(ImmutableMap.of(valueColumn, domain))));
        assertThat(filterResult).isEmpty();
    }

    private JdbcTableHandle applyCountAggregation(ConnectorSession session, ConnectorTableHandle tableHandle, List<List<ColumnHandle>> groupByColumns)
    {
        Optional<AggregationApplicationResult<ConnectorTableHandle>> aggResult = metadata.applyAggregation(
                session,
                tableHandle,
                ImmutableList.of(new AggregateFunction("count", BIGINT, List.of(), List.of(), false, Optional.empty())),
                ImmutableMap.of(),
                groupByColumns);
        assertThat(aggResult).isPresent();
        return (JdbcTableHandle) aggResult.get().getHandle();
    }

    private JdbcTableHandle applyConstraint(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint constraint)
    {
        Optional<ConstraintApplicationResult<ConnectorTableHandle>> filterResult = metadata.applyFilter(
                session,
                tableHandle,
                constraint);
        assertThat(filterResult).isPresent();
        return (JdbcTableHandle) filterResult.get().getHandle();
    }

    private static class GroupingSetsEnabledJdbcClient
            extends ForwardingJdbcClient
    {
        private final JdbcClient delegate;

        public GroupingSetsEnabledJdbcClient(JdbcClient jdbcClient)
        {
            this.delegate = jdbcClient;
        }

        @Override
        protected JdbcClient delegate()
        {
            return delegate;
        }

        @Override
        public boolean supportsGroupingSets()
        {
            return true;
        }
    }
}
