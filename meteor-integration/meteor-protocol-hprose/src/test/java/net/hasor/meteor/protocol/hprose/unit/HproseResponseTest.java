/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import hprose.io.HproseWriter;
import net.hasor.meteor.protocol.hprose.HproseUtils;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HproseResponseTest {
    @Test
    public void errorResponsePreservesRemoteCause() throws Exception {
        // 按 Hprose 错误帧格式写入远端诊断文本。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('E');
        new HproseWriter(out).writeString("远端失败");
        out.write('z');

        // 协议错误帧必须保留远端原因，不得被当作 null 返回值。
        ByteArrayInputStream input = new ByteArrayInputStream(out.toByteArray());
        IOException failure = assertThrows(IOException.class, () -> HproseUtils.decodeResponse(input));
        assertTrue(failure.getMessage().contains("远端失败"));
    }

    @Test
    public void emptyResponseIsRejected() throws Exception {
        // 空正文不是合法响应，必须给出明确的协议错误。
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);
        IOException failure = assertThrows(IOException.class, () -> HproseUtils.decodeResponse(input));
        assertTrue(failure.getMessage().contains("Invalid Hprose response"));
    }

    @Test
    public void unknownResponseTagIsRejected() throws Exception {
        // 未知标签不能被解析成普通结果或空返回值。
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] { '?' });
        IOException failure = assertThrows(IOException.class, () -> HproseUtils.decodeResponse(input));
        assertTrue(failure.getMessage().contains("Invalid Hprose response"));
    }
}
