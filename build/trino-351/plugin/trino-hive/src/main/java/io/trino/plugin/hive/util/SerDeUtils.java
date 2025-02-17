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
package io.trino.plugin.hive.util;

import com.google.common.annotations.VisibleForTesting;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Type;
import org.apache.hadoop.hive.common.type.HiveChar;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.io.TimestampWritable;
import org.apache.hadoop.hive.serde2.lazy.LazyDate;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.UnionObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BinaryObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BooleanObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.ByteObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DateObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.FloatObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveCharObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveVarcharObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.IntObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.ShortObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.TimestampObjectInspector;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.spi.type.Chars.truncateToLengthAndTrimSpaces;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.TinyintType.TINYINT;
import static java.lang.Float.floatToRawIntBits;
import static java.util.Objects.requireNonNull;

public final class SerDeUtils
{
    private SerDeUtils() {}

    public static Block getBlockObject(Type type, Object object, ObjectInspector objectInspector)
    {
        Block block = serializeObject(type, null, object, objectInspector);
        return requireNonNull(block, "serialized result is null");
    }

    public static Block serializeObject(Type type, BlockBuilder builder, Object object, ObjectInspector inspector)
    {
        return serializeObject(type, builder, object, inspector, true);
    }

    // This version supports optionally disabling the filtering of null map key, which should only be used for building test data sets
    // that contain null map keys.  For production, null map keys are not allowed.
    @VisibleForTesting
    public static Block serializeObject(Type type, BlockBuilder builder, Object object, ObjectInspector inspector, boolean filterNullMapKeys)
    {
        switch (inspector.getCategory()) {
            case PRIMITIVE:
                serializePrimitive(type, builder, object, (PrimitiveObjectInspector) inspector);
                return null;
            case LIST:
                return serializeList(type, builder, object, (ListObjectInspector) inspector);
            case MAP:
                return serializeMap(type, builder, object, (MapObjectInspector) inspector, filterNullMapKeys);
            case STRUCT:
                return serializeStruct(type, builder, object, (StructObjectInspector) inspector);
            case UNION:
                return serializeUnion(type, builder, object, (UnionObjectInspector) inspector);
        }
        throw new RuntimeException("Unknown object inspector category: " + inspector.getCategory());
    }

    private static void serializePrimitive(Type type, BlockBuilder builder, Object object, PrimitiveObjectInspector inspector)
    {
        requireNonNull(builder, "parent builder is null");

        if (object == null) {
            builder.appendNull();
            return;
        }

        switch (inspector.getPrimitiveCategory()) {
            case BOOLEAN:
                type.writeBoolean(builder, ((BooleanObjectInspector) inspector).get(object));
                return;
            case BYTE:
                type.writeLong(builder, ((ByteObjectInspector) inspector).get(object));
                return;
            case SHORT:
                type.writeLong(builder, ((ShortObjectInspector) inspector).get(object));
                return;
            case INT:
                type.writeLong(builder, ((IntObjectInspector) inspector).get(object));
                return;
            case LONG:
                type.writeLong(builder, ((LongObjectInspector) inspector).get(object));
                return;
            case FLOAT:
                type.writeLong(builder, floatToRawIntBits(((FloatObjectInspector) inspector).get(object)));
                return;
            case DOUBLE:
                type.writeDouble(builder, ((DoubleObjectInspector) inspector).get(object));
                return;
            case STRING:
                type.writeSlice(builder, Slices.utf8Slice(((StringObjectInspector) inspector).getPrimitiveJavaObject(object)));
                return;
            case VARCHAR:
                type.writeSlice(builder, Slices.utf8Slice(((HiveVarcharObjectInspector) inspector).getPrimitiveJavaObject(object).getValue()));
                return;
            case CHAR:
                HiveChar hiveChar = ((HiveCharObjectInspector) inspector).getPrimitiveJavaObject(object);
                type.writeSlice(builder, truncateToLengthAndTrimSpaces(Slices.utf8Slice(hiveChar.getValue()), ((CharType) type).getLength()));
                return;
            case DATE:
                type.writeLong(builder, formatDateAsLong(object, (DateObjectInspector) inspector));
                return;
            case TIMESTAMP:
                type.writeLong(builder, formatTimestampAsLong(object, (TimestampObjectInspector) inspector));
                return;
            case BINARY:
                type.writeSlice(builder, Slices.wrappedBuffer(((BinaryObjectInspector) inspector).getPrimitiveJavaObject(object)));
                return;
            case DECIMAL:
                DecimalType decimalType = (DecimalType) type;
                HiveDecimalWritable hiveDecimal = ((HiveDecimalObjectInspector) inspector).getPrimitiveWritableObject(object);
                if (decimalType.isShort()) {
                    type.writeLong(builder, DecimalUtils.getShortDecimalValue(hiveDecimal, decimalType.getScale()));
                }
                else {
                    type.writeSlice(builder, DecimalUtils.getLongDecimalValue(hiveDecimal, decimalType.getScale()));
                }
                return;
        }
        throw new RuntimeException("Unknown primitive type: " + inspector.getPrimitiveCategory());
    }

    private static Block serializeList(Type type, BlockBuilder builder, Object object, ListObjectInspector inspector)
    {
        List<?> list = inspector.getList(object);
        if (list == null) {
            requireNonNull(builder, "parent builder is null").appendNull();
            return null;
        }

        List<Type> typeParameters = type.getTypeParameters();
        checkArgument(typeParameters.size() == 1, "list must have exactly 1 type parameter");
        Type elementType = typeParameters.get(0);
        ObjectInspector elementInspector = inspector.getListElementObjectInspector();
        BlockBuilder currentBuilder;
        if (builder != null) {
            currentBuilder = builder.beginBlockEntry();
        }
        else {
            currentBuilder = elementType.createBlockBuilder(null, list.size());
        }

        for (Object element : list) {
            serializeObject(elementType, currentBuilder, element, elementInspector);
        }

        if (builder != null) {
            builder.closeEntry();
            return null;
        }
        else {
            Block resultBlock = currentBuilder.build();
            return resultBlock;
        }
    }

    private static Block serializeMap(Type type, BlockBuilder builder, Object object, MapObjectInspector inspector, boolean filterNullMapKeys)
    {
        Map<?, ?> map = inspector.getMap(object);
        if (map == null) {
            requireNonNull(builder, "parent builder is null").appendNull();
            return null;
        }

        List<Type> typeParameters = type.getTypeParameters();
        checkArgument(typeParameters.size() == 2, "map must have exactly 2 type parameter");
        Type keyType = typeParameters.get(0);
        Type valueType = typeParameters.get(1);
        ObjectInspector keyInspector = inspector.getMapKeyObjectInspector();
        ObjectInspector valueInspector = inspector.getMapValueObjectInspector();
        BlockBuilder currentBuilder;

        boolean builderSynthesized = false;
        if (builder == null) {
            builderSynthesized = true;
            builder = type.createBlockBuilder(null, 1);
        }
        currentBuilder = builder.beginBlockEntry();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            // Hive skips map entries with null keys
            if (!filterNullMapKeys || entry.getKey() != null) {
                serializeObject(keyType, currentBuilder, entry.getKey(), keyInspector);
                serializeObject(valueType, currentBuilder, entry.getValue(), valueInspector);
            }
        }

        builder.closeEntry();
        if (builderSynthesized) {
            return (Block) type.getObject(builder, 0);
        }
        else {
            return null;
        }
    }

    private static Block serializeStruct(Type type, BlockBuilder builder, Object object, StructObjectInspector inspector)
    {
        if (object == null) {
            requireNonNull(builder, "parent builder is null").appendNull();
            return null;
        }

        List<Type> typeParameters = type.getTypeParameters();
        List<? extends StructField> allStructFieldRefs = inspector.getAllStructFieldRefs();
        checkArgument(typeParameters.size() == allStructFieldRefs.size());
        BlockBuilder currentBuilder;

        boolean builderSynthesized = false;
        if (builder == null) {
            builderSynthesized = true;
            builder = type.createBlockBuilder(null, 1);
        }
        currentBuilder = builder.beginBlockEntry();

        for (int i = 0; i < typeParameters.size(); i++) {
            StructField field = allStructFieldRefs.get(i);
            serializeObject(typeParameters.get(i), currentBuilder, inspector.getStructFieldData(object, field), field.getFieldObjectInspector());
        }

        builder.closeEntry();
        if (builderSynthesized) {
            return (Block) type.getObject(builder, 0);
        }
        else {
            return null;
        }
    }

    // Use row blocks to represent union objects when reading
    private static Block serializeUnion(Type type, BlockBuilder builder, Object object, UnionObjectInspector inspector)
    {
        if (object == null) {
            requireNonNull(builder, "parent builder is null").appendNull();
            return null;
        }

        boolean builderSynthesized = false;
        if (builder == null) {
            builderSynthesized = true;
            builder = type.createBlockBuilder(null, 1);
        }

        BlockBuilder currentBuilder = builder.beginBlockEntry();

        byte tag = inspector.getTag(object);
        TINYINT.writeLong(currentBuilder, tag);

        List<Type> typeParameters = type.getTypeParameters();
        for (int i = 1; i < typeParameters.size(); i++) {
            if (i == tag + 1) {
                serializeObject(typeParameters.get(i), currentBuilder, inspector.getField(object), inspector.getObjectInspectors().get(tag));
            }
            else {
                currentBuilder.appendNull();
            }
        }

        builder.closeEntry();
        if (builderSynthesized) {
            return (Block) type.getObject(builder, 0);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static long formatDateAsLong(Object object, DateObjectInspector inspector)
    {
        if (object instanceof LazyDate) {
            return ((LazyDate) object).getWritableObject().getDays();
        }
        if (object instanceof DateWritable) {
            return ((DateWritable) object).getDays();
        }
        return inspector.getPrimitiveJavaObject(object).toEpochDay();
    }

    private static long formatTimestampAsLong(Object object, TimestampObjectInspector inspector)
    {
        if (object instanceof TimestampWritable) {
            return ((TimestampWritable) object).getTimestamp().getTime() * MICROSECONDS_PER_MILLISECOND;
        }
        return inspector.getPrimitiveJavaObject(object).toEpochMilli() * MICROSECONDS_PER_MILLISECOND;
    }
}
