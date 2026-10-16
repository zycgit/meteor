/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 附件转换的测试包内访问，不包含业务解析或断言。 */
public final class SoapAttachmentCodecAccess {
    private SoapAttachmentCodecAccess() {
    }

    public static Message encode(Document document, Map<Element, byte[]> binaries, String version, //
            String action, boolean xop, int maxBytes) throws Exception {
        SoapVersion selected = version(version);
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.encode(//
                document, binaries, selected, action, xop, maxBytes);
        return new Message(message.body(), message.contentType());
    }

    public static Message decode(byte[] body, String contentType, String version, int maxBytes) throws Exception {
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.decode(body, contentType, version(version), maxBytes);
        return new Message(message.body(), message.contentType());
    }

    private static SoapVersion version(String value) {
        if (StringUtils.equals("1.1", value)) {
            return SoapVersion.SOAP_11;
        }
        if (StringUtils.equals("1.2", value)) {
            return SoapVersion.SOAP_12;
        }
        throw new IllegalArgumentException("SOAP version must be 1.1 or 1.2: " + value);
    }

    public static final class Message {
        private final byte[] body;
        private final String contentType;

        private Message(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        public byte[] body() {
            return this.body;
        }

        public String contentType() {
            return this.contentType;
        }
    }
}
