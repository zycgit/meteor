/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol.rsf.codec;
import net.hasor.meteor.connector.protocol.rsf.codec.TestWireCodec;
import net.hasor.meteor.connector.protocol.rsf.codec.TestWireCodec.Pool;
import org.junit.Test;
import static org.junit.Assert.*;

public class PoolBlockTest {
    @Test
    public void poolOwnsItsInputArray() {
        // 修改输入数组不能改变已交给报文池的数据。
        byte[] value = { 7 };
        try (Pool pool = new Pool()) {
            pool.push(value);
            value[0] = 9;
            assertArrayEquals(new byte[] { 7 }, pool.read((short) 0));
        }
    }

    @Test
    public void poolPreservesNullAndEmptyValuesAsDistinctEntries() {
        // 非空数据之后的 null 和空数组应占据不同条目，不能打乱后续偏移。
        try (Pool pool = new Pool(); //
             Pool decoded = new Pool()) {
            pool.push(new byte[] { 7 });
            pool.push(null);
            pool.push(new byte[0]);

            // 编解码保留两种值的差异。
            decoded.decode(pool.encode());
            assertArrayEquals(new byte[] { 7 }, decoded.read((short) 0));
            assertNull(decoded.read((short) 1));
            assertEquals(0, decoded.read((short) 2).length);
        }
    }

    @Test
    public void decodedPoolSurvivesRepeatedSourceRelease() {
        // 源数据池与解码数据池具有独立生命周期。
        try (Pool pool = new Pool(); //
             Pool decoded = new Pool()) {
            pool.push(new byte[] { 7 });
            decoded.decode(pool.encode());

            // 重复释放源数据池后，解码数据仍可读取。
            pool.release();
            pool.release();
            assertArrayEquals(new byte[] { 7 }, decoded.read((short) 0));
        }
    }

    @Test
    public void negativePoolItemLengthIsRejected() {
        // -1 表示 null，其他负长度应在读取数据前拒绝。
        byte[] malformed = TestWireCodec.poolWithLength(-2);
        try (Pool pool = new Pool()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                    () -> pool.decode(malformed));
            assertTrue(failure.getMessage().contains("Invalid RSF pool item length"));
        }
    }

    @Test
    public void truncatedPoolBodyIsRejected() {
        // 长度表声明数据存在，但报文中没有对应字节。
        byte[] malformed = TestWireCodec.poolWithLength(100);
        try (Pool pool = new Pool()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                    () -> pool.decode(malformed));
            assertTrue(failure.getMessage().contains("RSF pool data length mismatch"));
        }
    }
}
