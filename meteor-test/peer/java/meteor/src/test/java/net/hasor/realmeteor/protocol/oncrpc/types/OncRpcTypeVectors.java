/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc.types;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.protocol.oncrpc.OncRpcChoice;
import net.hasor.example.protocol.oncrpc.OncRpcOptional;
import net.hasor.example.protocol.oncrpc.OncRpcSample;
import static org.junit.Assert.*;

/** 与 libtirpc 的独立 C 向量对应；每个向量只核对当前类型字段。 */
final class OncRpcTypeVectors {
    private OncRpcTypeVectors() {
    }

    static List<String> names() {
        // 顺序稳定，报告中的参数编号对应固定的类型和边界。
        List<String> names = new ArrayList<>();
        names.add("i32-signed-char-min");
        names.add("i32-signed-char-max");
        names.add("i32-unsigned-char-max");
        names.add("i32-short-min");
        names.add("i32-short-max");
        names.add("i32-unsigned-short-max");
        names.add("i64-long-min");
        names.add("i64-long-max");
        names.add("u64-unsigned-long-max");
        names.add("i32-min");
        names.add("i32-max");
        names.add("i32-zero");
        names.add("u32-zero");
        names.add("u32-high-bit");
        names.add("u32-max");
        names.add("i64-min");
        names.add("i64-max");
        names.add("i64-zero");
        names.add("u64-zero");
        names.add("u64-high-bit");
        names.add("u64-max");
        names.add("f32-zero");
        names.add("f32-negative-zero");
        names.add("f32-precision");
        names.add("f32-max");
        names.add("f32-negative-max");
        names.add("f32-min-normal");
        names.add("f32-min-subnormal");
        names.add("f32-infinity");
        names.add("f32-negative-infinity");
        names.add("f32-nan");
        names.add("f64-zero");
        names.add("f64-negative-zero");
        names.add("f64-precision");
        names.add("f64-max");
        names.add("f64-negative-max");
        names.add("f64-min-normal");
        names.add("f64-min-subnormal");
        names.add("f64-infinity");
        names.add("f64-negative-infinity");
        names.add("f64-nan");
        names.add("bool-true");
        names.add("bool-false");
        names.add("string-empty");
        names.add("string-ascii");
        names.add("string-max");
        names.add("opaque-empty");
        names.add("opaque-all-bytes");
        names.add("fixed-opaque-high-bits");
        names.add("array-empty");
        names.add("array-boundaries");
        names.add("array-max");
        names.add("fixed-array-boundaries");
        names.add("enum-red");
        names.add("enum-green");
        names.add("enum-blue");
        names.add("union-int");
        names.add("union-text");
        names.add("union-default");
        names.add("pointer-null");
        names.add("pointer-single");
        names.add("pointer-recursive");
        names.add("string-unicode-rejected");
        return names;
    }

    static OncRpcSample sample(String vector) {
        // 未选中的字段保持最小合法值，不将整个 DTO 的变化混为一个场景。
        OncRpcSample value = new OncRpcSample();
        value.setUnsignedHyper(BigInteger.ZERO);
        value.setText("");
        value.setFixedOpaque(new byte[5]);
        value.setBytes(new byte[0]);
        value.setFixedNumbers(List.of(0, 0, 0));
        value.setNumbers(List.of());
        value.setColour(2);
        OncRpcChoice choice = new OncRpcChoice();
        choice.setTag(99);
        value.setChoice(choice);
        switch (vector) {
            case "i32-signed-char-min": {
                value.setSignedValue(Byte.MIN_VALUE);
                break;
            }
            case "i32-signed-char-max": {
                value.setSignedValue(Byte.MAX_VALUE);
                break;
            }
            case "i32-unsigned-char-max": {
                value.setSignedValue(255);
                break;
            }
            case "i32-short-min": {
                value.setSignedValue(Short.MIN_VALUE);
                break;
            }
            case "i32-short-max": {
                value.setSignedValue(Short.MAX_VALUE);
                break;
            }
            case "i32-unsigned-short-max": {
                value.setSignedValue(65535);
                break;
            }
            case "i64-long-min": {
                value.setSignedHyper(Long.MIN_VALUE);
                break;
            }
            case "i64-long-max": {
                value.setSignedHyper(Long.MAX_VALUE);
                break;
            }
            case "u64-unsigned-long-max": {
                value.setUnsignedHyper(new BigInteger("18446744073709551615"));
                break;
            }
            case "i32-min": {
                value.setSignedValue(Integer.MIN_VALUE);
                break;
            }
            case "i32-max": {
                value.setSignedValue(Integer.MAX_VALUE);
                break;
            }
            case "i32-zero": {
                value.setSignedValue(0);
                break;
            }
            case "u32-zero": {
                value.setUnsignedValue(0);
                break;
            }
            case "u32-high-bit": {
                value.setUnsignedValue(2147483648L);
                break;
            }
            case "u32-max": {
                value.setUnsignedValue(0xffffffffL);
                break;
            }
            case "i64-min": {
                value.setSignedHyper(Long.MIN_VALUE);
                break;
            }
            case "i64-max": {
                value.setSignedHyper(Long.MAX_VALUE);
                break;
            }
            case "i64-zero": {
                value.setSignedHyper(0);
                break;
            }
            case "u64-zero": {
                value.setUnsignedHyper(BigInteger.ZERO);
                break;
            }
            case "u64-high-bit": {
                value.setUnsignedHyper(BigInteger.ONE.shiftLeft(63));
                break;
            }
            case "u64-max": {
                value.setUnsignedHyper(new BigInteger("18446744073709551615"));
                break;
            }
            case "f32-zero": {
                value.setFloatValue(0.0f);
                break;
            }
            case "f32-negative-zero": {
                value.setFloatValue(-0.0f);
                break;
            }
            case "f32-precision": {
                value.setFloatValue(Float.intBitsToFloat(0x3f800001));
                break;
            }
            case "f32-max": {
                value.setFloatValue(Float.MAX_VALUE);
                break;
            }
            case "f32-negative-max": {
                value.setFloatValue(-Float.MAX_VALUE);
                break;
            }
            case "f32-min-normal": {
                value.setFloatValue(Float.MIN_NORMAL);
                break;
            }
            case "f32-min-subnormal": {
                value.setFloatValue(Float.MIN_VALUE);
                break;
            }
            case "f32-infinity": {
                value.setFloatValue(Float.POSITIVE_INFINITY);
                break;
            }
            case "f32-negative-infinity": {
                value.setFloatValue(Float.NEGATIVE_INFINITY);
                break;
            }
            case "f32-nan": {
                value.setFloatValue(Float.NaN);
                break;
            }
            case "f64-zero": {
                value.setDoubleValue(0.0);
                break;
            }
            case "f64-negative-zero": {
                value.setDoubleValue(-0.0);
                break;
            }
            case "f64-precision": {
                value.setDoubleValue(Double.longBitsToDouble(0x3ff0000000000001L));
                break;
            }
            case "f64-max": {
                value.setDoubleValue(Double.MAX_VALUE);
                break;
            }
            case "f64-negative-max": {
                value.setDoubleValue(-Double.MAX_VALUE);
                break;
            }
            case "f64-min-normal": {
                value.setDoubleValue(Double.MIN_NORMAL);
                break;
            }
            case "f64-min-subnormal": {
                value.setDoubleValue(Double.MIN_VALUE);
                break;
            }
            case "f64-infinity": {
                value.setDoubleValue(Double.POSITIVE_INFINITY);
                break;
            }
            case "f64-negative-infinity": {
                value.setDoubleValue(Double.NEGATIVE_INFINITY);
                break;
            }
            case "f64-nan": {
                value.setDoubleValue(Double.NaN);
                break;
            }
            case "bool-true": {
                value.setBoolValue(true);
                break;
            }
            case "bool-false": {
                value.setBoolValue(false);
                break;
            }
            case "string-empty": {
                value.setText("");
                break;
            }
            case "string-ascii": {
                value.setText("Meteor ASCII !\"#~");
                break;
            }
            case "string-max": {
                value.setText("x".repeat(4096));
                break;
            }
            case "opaque-empty": {
                value.setBytes(new byte[0]);
                break;
            }
            case "opaque-all-bytes": {
                byte[] bytes = new byte[256];
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) index;
                }
                value.setBytes(bytes);
                break;
            }
            case "fixed-opaque-high-bits": {
                value.setFixedOpaque(new byte[] { 0, 127, -128, -2, -1 });
                break;
            }
            case "array-empty": {
                value.setNumbers(List.of());
                break;
            }
            case "array-boundaries": {
                value.setNumbers(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
                break;
            }
            case "array-max": {
                List<Integer> numbers = new ArrayList<>();
                for (int index = 0; index < 128; index++) {
                    numbers.add(index - 64);
                }
                value.setNumbers(numbers);
                break;
            }
            case "fixed-array-boundaries": {
                value.setFixedNumbers(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
                break;
            }
            case "enum-red": {
                value.setColour(2);
                break;
            }
            case "enum-green": {
                value.setColour(3);
                break;
            }
            case "enum-blue": {
                value.setColour(5);
                break;
            }
            case "union-int": {
                value.getChoice().setTag(1);
                value.getChoice().setNumber(Integer.MIN_VALUE);
                break;
            }
            case "union-text": {
                value.getChoice().setTag(2);
                value.getChoice().setText("union-text");
                break;
            }
            case "union-default": {
                value.getChoice().setTag(99);
                break;
            }
            case "pointer-null": {
                value.setOptional(null);
                break;
            }
            case "pointer-single": {
                OncRpcOptional node = new OncRpcOptional();
                node.setValue(Integer.MIN_VALUE);
                value.setOptional(node);
                break;
            }
            case "pointer-recursive": {
                OncRpcOptional node = new OncRpcOptional();
                node.setValue(Integer.MIN_VALUE);
                OncRpcOptional tail = new OncRpcOptional();
                tail.setValue(Integer.MAX_VALUE);
                node.setNext(tail);
                value.setOptional(node);
                break;
            }
            case "string-unicode-rejected": {
                value.setText("中文");
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown C type vector: " + vector);
        }
        return value;
    }

    static void assertField(String vector, OncRpcSample expected, OncRpcSample actual) {
        // IEEE 位断言保留负零和 NaN；每个其余分支只检查选中的一个 XDR 字段。
        if (vector.startsWith("i32-")) {
            assertEquals(expected.getSignedValue(), actual.getSignedValue());
        } else if (vector.startsWith("u32-")) {
            assertEquals(expected.getUnsignedValue(), actual.getUnsignedValue());
        } else if (vector.startsWith("i64-")) {
            assertEquals(expected.getSignedHyper(), actual.getSignedHyper());
        } else if (vector.startsWith("u64-")) {
            assertEquals(expected.getUnsignedHyper(), actual.getUnsignedHyper());
        } else if (vector.startsWith("f32-")) {
            int expectedBits = Float.floatToRawIntBits(expected.getFloatValue());
            int actualBits = Float.floatToRawIntBits(actual.getFloatValue());
            assertEquals(expectedBits, actualBits);
        } else if (vector.startsWith("f64-")) {
            long expectedBits = Double.doubleToRawLongBits(expected.getDoubleValue());
            long actualBits = Double.doubleToRawLongBits(actual.getDoubleValue());
            assertEquals(expectedBits, actualBits);
        } else if (vector.startsWith("bool-")) {
            assertEquals(expected.getBoolValue(), actual.getBoolValue());
        } else if (vector.startsWith("string-")) {
            assertEquals(expected.getText(), actual.getText());
        } else if (vector.startsWith("opaque-")) {
            assertArrayEquals(expected.getBytes(), actual.getBytes());
        } else if (vector.startsWith("fixed-opaque-")) {
            assertArrayEquals(expected.getFixedOpaque(), actual.getFixedOpaque());
        } else if (vector.startsWith("array-")) {
            assertEquals(expected.getNumbers(), actual.getNumbers());
        } else if (vector.startsWith("fixed-array-")) {
            assertEquals(expected.getFixedNumbers(), actual.getFixedNumbers());
        } else if (vector.startsWith("enum-")) {
            assertEquals(expected.getColour(), actual.getColour());
        } else if (vector.startsWith("union-")) {
            assertEquals(expected.getChoice().getTag(), actual.getChoice().getTag());
            if (expected.getChoice().getTag() == 1) {
                assertEquals(expected.getChoice().getNumber(), actual.getChoice().getNumber());
            } else if (expected.getChoice().getTag() == 2) {
                assertEquals(expected.getChoice().getText(), actual.getChoice().getText());
            }
        } else if (vector.startsWith("pointer-")) {
            OncRpcOptional expectedNode = expected.getOptional();
            OncRpcOptional actualNode = actual.getOptional();
            while (expectedNode != null) {
                assertNotNull(actualNode);
                assertEquals(expectedNode.getValue(), actualNode.getValue());
                expectedNode = expectedNode.getNext();
                actualNode = actualNode.getNext();
            }
            assertNull(actualNode);
        } else {
            fail("Missing field assertion for " + vector);
        }
    }
}
