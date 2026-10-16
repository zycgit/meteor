/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import org.junit.Test;
import static org.junit.Assert.*;

public class RsfV1AttributePoolTest {
    @Test
    public void poolOwnsItsInputArray() {
        // 修改输入数组不能改变已交给报文池的数据。
        byte[] value = { 7 };
        RsfV1AttributePool pool = new RsfV1AttributePool();
        try {
            pool.addValue(value);
            value[0] = 9;
            assertArrayEquals(new byte[] { 7 }, pool.getValue((short) 0));
        } finally {
            pool.release();
        }
    }

    @Test
    public void poolPreservesNullAndEmptyValuesAsDistinctEntries() {
        // 非空数据之后的 null 和空数组应占据不同条目，不能打乱后续偏移。
        RsfV1AttributePool pool = new RsfV1AttributePool();
        RsfV1AttributePool decoded = new RsfV1AttributePool();
        try {
            pool.addValue(new byte[] { 7 });
            pool.addValue(null);
            pool.addValue(new byte[0]);

            // 编解码保留两种值的差异。
            RsfV1WireBuffer encoded = RsfV1WireBuffer.output();
            pool.writeTo(encoded);
            decoded.readFrom(RsfV1WireBuffer.input(encoded.toByteArray()));
            assertArrayEquals(new byte[] { 7 }, decoded.getValue((short) 0));
            assertNull(decoded.getValue((short) 1));
            assertEquals(0, decoded.getValue((short) 2).length);
        } finally {
            decoded.release();
            pool.release();
        }
    }

    @Test
    public void decodedPoolSurvivesRepeatedSourceRelease() {
        // 源数据池与解码数据池具有独立生命周期。
        RsfV1AttributePool pool = new RsfV1AttributePool();
        RsfV1AttributePool decoded = new RsfV1AttributePool();
        try {
            pool.addValue(new byte[] { 7 });
            RsfV1WireBuffer encoded = RsfV1WireBuffer.output();
            pool.writeTo(encoded);
            decoded.readFrom(RsfV1WireBuffer.input(encoded.toByteArray()));

            // 重复释放源数据池后，解码数据仍可读取。
            pool.release();
            pool.release();
            assertArrayEquals(new byte[] { 7 }, decoded.getValue((short) 0));
        } finally {
            decoded.release();
            pool.release();
        }
    }

    @Test
    public void negativePoolItemLengthIsRejected() {
        // -1 表示 null，其他负长度应在读取数据前拒绝。
        RsfV1WireBuffer output = RsfV1WireBuffer.output();
        output.writeShort(1).writeInt(-2);
        RsfV1WireBuffer malformed = RsfV1WireBuffer.input(output.toByteArray());
        RsfV1AttributePool pool = new RsfV1AttributePool();
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                    () -> pool.readFrom(malformed));
            assertTrue(failure.getMessage().contains("Invalid RSF pool item length"));
        } finally {
            pool.release();
        }
    }

    @Test
    public void truncatedPoolBodyIsRejected() {
        // 长度表声明数据存在，但报文中没有对应字节。
        RsfV1WireBuffer output = RsfV1WireBuffer.output();
        output.writeShort(1).writeInt(100);
        RsfV1WireBuffer malformed = RsfV1WireBuffer.input(output.toByteArray());
        RsfV1AttributePool pool = new RsfV1AttributePool();
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                    () -> pool.readFrom(malformed));
            assertTrue(failure.getMessage().contains("RSF pool data length mismatch"));
        } finally {
            pool.release();
        }
    }
}
