/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ThriftMessageTest {
    @Test
    public void constructorPreservesMessageFields() {
        // 使用独立的元信息与正文构造消息。
        byte[] data = new byte[] { 1, 2, 3 };
        ThriftMessage message = new ThriftMessage("compact", "echo", (byte) 2, 37, data);

        // 所有公开字段都保留构造时提供的值。
        assertEquals("compact", message.encoding());
        assertEquals("echo", message.methodName());
        assertEquals((byte) 2, message.messageType());
        assertEquals(37, message.sequenceId());
        assertArrayEquals(data, message.data());
    }

    @Test
    public void constructorCopiesData() {
        // 消息接收调用方持有的正文数组。
        byte[] data = new byte[] { 1, 2, 3 };
        ThriftMessage message = new ThriftMessage("binary", "echo", (byte) 1, 7, data);

        // 修改原数组不能影响消息已经保存的正文。
        data[0] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3 }, message.data());
    }

    @Test
    public void getDataReturnsIndependentCopy() {
        // 读取一次消息正文，获得调用方可修改的数组。
        ThriftMessage message = new ThriftMessage("binary", "echo", (byte) 1, 7, new byte[] { 1, 2, 3 });
        byte[] firstRead = message.data();

        // 修改读取结果后，再次读取仍得到原始正文与独立数组。
        firstRead[0] = 9;
        byte[] secondRead = message.data();
        assertNotSame(firstRead, secondRead);
        assertArrayEquals(new byte[] { 1, 2, 3 }, secondRead);
    }
}
