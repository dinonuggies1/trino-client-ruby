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
package io.trino.plugin.kafka;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.base.classloader.ClassLoaderSafeConnectorPageSinkProvider;
import io.trino.plugin.base.classloader.ClassLoaderSafeConnectorRecordSetProvider;
import io.trino.plugin.base.classloader.ClassLoaderSafeConnectorSplitManager;
import io.trino.plugin.base.classloader.ForClassLoaderSafe;
import io.trino.plugin.kafka.schema.confluent.ConfluentModule;
import io.trino.plugin.kafka.schema.confluent.ConfluentSchemaRegistryTableDescriptionSupplier;
import io.trino.plugin.kafka.schema.file.FileTableDescriptionSupplier;
import io.trino.plugin.kafka.schema.file.FileTableDescriptionSupplierModule;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorPageSinkProvider;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeId;
import io.trino.spi.type.TypeManager;

import javax.inject.Inject;

import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.json.JsonBinder.jsonBinder;
import static io.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static java.util.Objects.requireNonNull;

public class KafkaConnectorModule
        extends AbstractConfigurationAwareModule
{
    @Override
    public void setup(Binder binder)
    {
        binder.bind(ConnectorMetadata.class).to(KafkaMetadata.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorSplitManager.class).annotatedWith(ForClassLoaderSafe.class).to(KafkaSplitManager.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorSplitManager.class).to(ClassLoaderSafeConnectorSplitManager.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorRecordSetProvider.class).annotatedWith(ForClassLoaderSafe.class).to(KafkaRecordSetProvider.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorRecordSetProvider.class).to(ClassLoaderSafeConnectorRecordSetProvider.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorPageSinkProvider.class).annotatedWith(ForClassLoaderSafe.class).to(KafkaPageSinkProvider.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorPageSinkProvider.class).to(ClassLoaderSafeConnectorPageSinkProvider.class).in(Scopes.SINGLETON);
        binder.bind(KafkaConnector.class).in(Scopes.SINGLETON);
        binder.bind(KafkaInternalFieldManager.class).in(Scopes.SINGLETON);
        binder.bind(KafkaSessionProperties.class).in(Scopes.SINGLETON);
        binder.bind(KafkaFilterManager.class).in(Scopes.SINGLETON);

        configBinder(binder).bindConfig(KafkaConfig.class);
        bindTopicSchemaProviderModule(FileTableDescriptionSupplier.NAME, new FileTableDescriptionSupplierModule());
        bindTopicSchemaProviderModule(ConfluentSchemaRegistryTableDescriptionSupplier.NAME, new ConfluentModule());
        newSetBinder(binder, SessionPropertiesProvider.class).addBinding().to(KafkaSessionProperties.class).in(Scopes.SINGLETON);
        jsonBinder(binder).addDeserializerBinding(Type.class).to(TypeDeserializer.class);
        jsonCodecBinder(binder).bindJsonCodec(KafkaTopicDescription.class);
    }

    private static final class TypeDeserializer
            extends FromStringDeserializer<Type>
    {
        private static final long serialVersionUID = 1L;

        private final TypeManager typeManager;

        @Inject
        public TypeDeserializer(TypeManager typeManager)
        {
            super(Type.class);
            this.typeManager = requireNonNull(typeManager, "typeManager is null");
        }

        @Override
        protected Type _deserialize(String value, DeserializationContext context)
        {
            return typeManager.getType(TypeId.of(value));
        }
    }

    public void bindTopicSchemaProviderModule(String name, Module module)
    {
        install(installModuleIf(
                KafkaConfig.class,
                kafkaConfig -> name.equalsIgnoreCase(kafkaConfig.getTableDescriptionSupplier()),
                module));
    }
}
