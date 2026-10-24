/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import java.io.IOException;
import net.hasor.example.contract.types.TypeRecord;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** 每次只断言当前向量对应的业务字段和服务端生成的收据。 */
public final class TypeValueAssertions {
    private TypeValueAssertions() {
    }

    public static void assertVector(String vector, TypeValue expected, TypeValue actual) throws IOException {
        // caseId 和收据共同证明响应来自对应的服务调用，而不是本地复制输入。
        assertNotNull(actual);
        assertEquals(vector, actual.getCaseId());
        assertEquals("accepted:" + vector, actual.getReceipt());

        // 每个参数化实例只检查自己的类型语义，不将其它默认字段混成额外测试场景。
        String type = TypeValueVectors.type(vector);
        switch (type) {
            case "boolean":
                assertEquals(expected.isBooleanValue(), actual.isBooleanValue());
                break;
            case "int8":
                assertEquals(expected.getInt8Value(), actual.getInt8Value());
                break;
            case "int16":
                assertEquals(expected.getInt16Value(), actual.getInt16Value());
                break;
            case "int32":
                assertEquals(expected.getInt32Value(), actual.getInt32Value());
                break;
            case "int64":
                assertEquals(expected.getInt64Value(), actual.getInt64Value());
                break;
            case "uint8":
            case "uint16":
            case "uint32":
            case "uint64":
                assertEquals(expected.getUnsigned64Value(), actual.getUnsigned64Value());
                break;
            case "float32":
                int expectedSingleBits = Float.floatToRawIntBits(expected.getFloat32Value());
                int actualSingleBits = Float.floatToRawIntBits(actual.getFloat32Value());
                assertEquals(expectedSingleBits, actualSingleBits);
                break;
            case "float64":
                long expectedDoubleBits = Double.doubleToRawLongBits(expected.getFloat64Value());
                long actualDoubleBits = Double.doubleToRawLongBits(actual.getFloat64Value());
                assertEquals(expectedDoubleBits, actualDoubleBits);
                break;
            case "text":
                assertEquals(expected.getTextValue(), actual.getTextValue());
                break;
            case "binary":
                assertArrayEquals(expected.getBinaryValue(), actual.getBinaryValue());
                break;
            case "enum":
                assertEquals(expected.getEnumValue(), actual.getEnumValue());
                break;
            case "list":
                assertEquals(expected.getListValue(), actual.getListValue());
                break;
            case "map":
                assertEquals(expected.getMapValue(), actual.getMapValue());
                break;
            case "record":
                TypeRecord expectedRecord = expected.getRecordValue();
                TypeRecord actualRecord = actual.getRecordValue();
                assertNotNull(actualRecord);
                assertEquals(expectedRecord.getIndex(), actualRecord.getIndex());
                assertEquals(expectedRecord.getName(), actualRecord.getName());
                break;
            case "optional":
                assertEquals(expected.getOptionalValue(), actual.getOptionalValue());
                break;
            default:
                throw new IllegalArgumentException("Unknown shared type vector: " + type);
        }
    }
}
