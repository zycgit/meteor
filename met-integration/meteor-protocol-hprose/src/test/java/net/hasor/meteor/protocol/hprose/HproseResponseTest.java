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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HproseResponseTest {
    @Test
    public void errorAndMalformedResponsesCannotBecomeSuccessfulNulls() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('E');
        new HproseWriter(out).writeString("远端失败");
        out.write('z');
        try {
            HproseUtils.decodeResponse(new ByteArrayInputStream(out.toByteArray()));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("远端失败"));
        }
        for (byte[] malformed : new byte[][] { new byte[0], new byte[] { '?' } }) {
            try {
                HproseUtils.decodeResponse(new ByteArrayInputStream(malformed));
                fail();
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Invalid Hprose response"));
            }
        }
    }
}
