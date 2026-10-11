/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import hprose.io.HproseWriter;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HproseResponseTest {
    @Test
    public void errorAndMalformedResponsesCannotBecomeSuccessfulNulls() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('E');
        new HproseWriter(out).writeString("远端失败");
        out.write('z');

        // 协议错误帧必须保留远端原因，不得被当作 null 返回值。
        ByteArrayInputStream input = new ByteArrayInputStream(out.toByteArray());
        IOException failure = assertThrows(IOException.class, () -> HproseUtils.decodeResponse(input));
        assertTrue(failure.getMessage().contains("远端失败"));

        // 缺失响应标记与未知响应标记都属于格式错误。
        for (byte[] malformed : new byte[][] { new byte[0], new byte[] { '?' } }) {
            ByteArrayInputStream invalid = new ByteArrayInputStream(malformed);
            IOException error = assertThrows(IOException.class, () -> HproseUtils.decodeResponse(invalid));
            assertTrue(error.getMessage().contains("Invalid Hprose response"));
        }
    }
}
