/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.util;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.AtomicDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeFamily;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.MultisetType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.TimeType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.table.types.logical.TypeInformationRawType;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;

import java.util.List;

/**
 * Converts an Avro schema into Flink's type information. It uses {@link org.apache.flink.api.java.typeutils.RowTypeInfo} for
 * representing objects and converts Avro types into types that are compatible with Flink's Table &
 * SQL API.
 *
 * <p>Note: Changes in this class need to be kept in sync with the corresponding runtime classes
 * {@code org.apache.flink.formats.avro.AvroRowDeserializationSchema} and {@code org.apache.flink.formats.avro.AvroRowSerializationSchema}.
 *
 * <p>NOTE: reference from Flink release 1.12.0, should remove when Flink version upgrade to that.
 */
public class AvroSchemaConverter {

  /**
   * Converts an Avro schema {@code schema} into a nested row structure with deterministic field order and
   * data types that are compatible with Flink's Table & SQL API.
   *
   * @param schema Avro schema definition
   * @return data type matching the schema
   */
  public static DataType convertToDataType(Schema schema) {
    switch (schema.getType()) {
      case RECORD:
        final List<Schema.Field> schemaFields = schema.getFields();

        final DataTypes.Field[] fields = new DataTypes.Field[schemaFields.size()];
        for (int i = 0; i < schemaFields.size(); i++) {
          final Schema.Field field = schemaFields.get(i);
          fields[i] = DataTypes.FIELD(field.name(), convertToDataType(field.schema()));
        }
        return DataTypes.ROW(fields).notNull();
      case ENUM:
        return DataTypes.STRING().notNull();
      case ARRAY:
        return DataTypes.ARRAY(convertToDataType(schema.getElementType())).notNull();
      case MAP:
        return DataTypes.MAP(
                DataTypes.STRING().notNull(),
                convertToDataType(schema.getValueType()))
            .notNull();
      case UNION:
        final Schema actualSchema;
        final boolean nullable;
        if (schema.getTypes().size() == 2
            && schema.getTypes().get(0).getType() == Schema.Type.NULL) {
          actualSchema = schema.getTypes().get(1);
          nullable = true;
        } else if (schema.getTypes().size() == 2
            && schema.getTypes().get(1).getType() == Schema.Type.NULL) {
          actualSchema = schema.getTypes().get(0);
          nullable = true;
        } else if (schema.getTypes().size() == 1) {
          actualSchema = schema.getTypes().get(0);
          nullable = false;
        } else {
          // use Kryo for serialization
          return new AtomicDataType(
              new TypeInformationRawType<>(false, Types.GENERIC(Object.class)));
        }
        DataType converted = convertToDataType(actualSchema);
        return nullable ? converted.nullable() : converted;
      case FIXED:
        // logical decimal type
        if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
          final LogicalTypes.Decimal decimalType =
              (LogicalTypes.Decimal) schema.getLogicalType();
          return DataTypes.DECIMAL(decimalType.getPrecision(), decimalType.getScale())
              .notNull();
        }
        // convert fixed size binary data to primitive byte arrays
        return DataTypes.VARBINARY(schema.getFixedSize()).notNull();
      case STRING:
        // convert Avro's Utf8/CharSequence to String
        return DataTypes.STRING().notNull();
      case BYTES:
        // logical decimal type
        if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
          final LogicalTypes.Decimal decimalType =
              (LogicalTypes.Decimal) schema.getLogicalType();
          return DataTypes.DECIMAL(decimalType.getPrecision(), decimalType.getScale())
              .notNull();
        }
        return DataTypes.BYTES().notNull();
      case INT:
        // logical date and time type
        final org.apache.avro.LogicalType logicalType = schema.getLogicalType();
        if (logicalType == LogicalTypes.date()) {
          return DataTypes.DATE().notNull();
        } else if (logicalType == LogicalTypes.timeMillis()) {
          return DataTypes.TIME(3).notNull();
        }
        return DataTypes.INT().notNull();
      case LONG:
        // logical timestamp type
        if (schema.getLogicalType() == LogicalTypes.timestampMillis()) {
          return DataTypes.TIMESTAMP(3).notNull();
        } else if (schema.getLogicalType() == LogicalTypes.timestampMicros()) {
          return DataTypes.TIMESTAMP(6).notNull();
        } else if (schema.getLogicalType() == LogicalTypes.timeMillis()) {
          return DataTypes.TIME(3).notNull();
        } else if (schema.getLogicalType() == LogicalTypes.timeMicros()) {
          return DataTypes.TIME(6).notNull();
        }
        return DataTypes.BIGINT().notNull();
      case FLOAT:
        return DataTypes.FLOAT().notNull();
      case DOUBLE:
        return DataTypes.DOUBLE().notNull();
      case BOOLEAN:
        return DataTypes.BOOLEAN().notNull();
      case NULL:
        return DataTypes.NULL();
      default:
        throw new IllegalArgumentException("Unsupported Avro type '" + schema.getType() + "'.");
    }
  }

  /**
   * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
   *
   * <p>Use "record" as the type name.
   *
   * @param schema the schema type, usually it should be the top level record type, e.g. not a
   *               nested type
   * @return Avro's {@link Schema} matching this logical type.
   */
  public static Schema convertToSchema(LogicalType schema) {
    return convertToSchema(schema, "record");
  }

  /**
   * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
   *
   * <p>The "{rowName}_" is used as the nested row type name prefix in order to generate the right
   * schema. Nested record type that only differs with type name is still compatible.
   *
   * @param logicalType logical type
   * @param rowName     the record name
   * @return Avro's {@link Schema} matching this logical type.
   */
  public static Schema convertToSchema(LogicalType logicalType, String rowName) {
    int precision;
    boolean nullable = logicalType.isNullable();
    switch (logicalType.getTypeRoot()) {
      case NULL:
        return SchemaBuilder.builder().nullType();
      case BOOLEAN:
        Schema bool = SchemaBuilder.builder().booleanType();
        return nullable ? nullableSchema(bool) : bool;
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        Schema integer = SchemaBuilder.builder().intType();
        return nullable ? nullableSchema(integer) : integer;
      case BIGINT:
        Schema bigint = SchemaBuilder.builder().longType();
        return nullable ? nullableSchema(bigint) : bigint;
      case FLOAT:
        Schema f = SchemaBuilder.builder().floatType();
        return nullable ? nullableSchema(f) : f;
      case DOUBLE:
        Schema d = SchemaBuilder.builder().doubleType();
        return nullable ? nullableSchema(d) : d;
      case CHAR:
      case VARCHAR:
        Schema str = SchemaBuilder.builder().stringType();
        return nullable ? nullableSchema(str) : str;
      case BINARY:
      case VARBINARY:
        Schema binary = SchemaBuilder.builder().bytesType();
        return nullable ? nullableSchema(binary) : binary;
      case TIMESTAMP_WITHOUT_TIME_ZONE:
        // use long to represents Timestamp
        final TimestampType timestampType = (TimestampType) logicalType;
        precision = timestampType.getPrecision();
        org.apache.avro.LogicalType avroLogicalType;
        if (precision <= 3) {
          avroLogicalType = LogicalTypes.timestampMillis();
        } else if (precision <= 6) {
          avroLogicalType = LogicalTypes.timestampMicros();
        } else {
          throw new IllegalArgumentException(
              "Avro does not support TIMESTAMP type with precision: "
                  + precision
                  + ", it only supports precision less than 6.");
        }
        Schema timestamp = avroLogicalType.addToSchema(SchemaBuilder.builder().longType());
        return nullable ? nullableSchema(timestamp) : timestamp;
      case DATE:
        // use int to represents Date
        Schema date = LogicalTypes.date().addToSchema(SchemaBuilder.builder().intType());
        return nullable ? nullableSchema(date) : date;
      case TIME_WITHOUT_TIME_ZONE:
        precision = ((TimeType) logicalType).getPrecision();
        if (precision > 3) {
          throw new IllegalArgumentException(
              "Avro does not support TIME type with precision: "
                  + precision
                  + ", it only supports precision less than 3.");
        }
        // use int to represents Time, we only support millisecond when deserialization
        Schema time =
            LogicalTypes.timeMillis().addToSchema(SchemaBuilder.builder().intType());
        return nullable ? nullableSchema(time) : time;
      case DECIMAL:
        DecimalType decimalType = (DecimalType) logicalType;
        // store BigDecimal as byte[]
        Schema decimal =
            LogicalTypes.decimal(decimalType.getPrecision(), decimalType.getScale())
                .addToSchema(SchemaBuilder.builder().bytesType());
        return nullable ? nullableSchema(decimal) : decimal;
      case ROW:
        RowType rowType = (RowType) logicalType;
        List<String> fieldNames = rowType.getFieldNames();
        // we have to make sure the record name is different in a Schema
        SchemaBuilder.FieldAssembler<Schema> builder =
            SchemaBuilder.builder().record(rowName).fields();
        for (int i = 0; i < rowType.getFieldCount(); i++) {
          String fieldName = fieldNames.get(i);
          LogicalType fieldType = rowType.getTypeAt(i);
          SchemaBuilder.GenericDefault<Schema> fieldBuilder =
              builder.name(fieldName)
                  .type(convertToSchema(fieldType, rowName + "_" + fieldName));

          if (fieldType.isNullable()) {
            builder = fieldBuilder.withDefault(null);
          } else {
            builder = fieldBuilder.noDefault();
          }
        }
        Schema record = builder.endRecord();
        return nullable ? nullableSchema(record) : record;
      case MULTISET:
      case MAP:
        Schema map =
            SchemaBuilder.builder()
                .map()
                .values(
                    convertToSchema(
                        extractValueTypeToAvroMap(logicalType), rowName));
        return nullable ? nullableSchema(map) : map;
      case ARRAY:
        ArrayType arrayType = (ArrayType) logicalType;
        Schema array =
            SchemaBuilder.builder()
                .array()
                .items(convertToSchema(arrayType.getElementType(), rowName));
        return nullable ? nullableSchema(array) : array;
      case RAW:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      default:
        throw new UnsupportedOperationException(
            "Unsupported to derive Schema for type: " + logicalType);
    }
  }

  public static LogicalType extractValueTypeToAvroMap(LogicalType type) {
    LogicalType keyType;
    LogicalType valueType;
    if (type instanceof MapType) {
      MapType mapType = (MapType) type;
      keyType = mapType.getKeyType();
      valueType = mapType.getValueType();
    } else {
      MultisetType multisetType = (MultisetType) type;
      keyType = multisetType.getElementType();
      valueType = new IntType();
    }
    if (!LogicalTypeChecks.hasFamily(keyType, LogicalTypeFamily.CHARACTER_STRING)) {
      throw new UnsupportedOperationException(
          "Avro format doesn't support non-string as key type of map. "
              + "The key type is: "
              + keyType.asSummaryString());
    }
    return valueType;
  }

  /**
   * Returns schema with nullable true.
   */
  private static Schema nullableSchema(Schema schema) {
    return schema.isNullable()
        ? schema
        : Schema.createUnion(SchemaBuilder.builder().nullType(), schema);
  }
}

