/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Type;
import javax.xml.namespace.QName;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 为单元测试提供 SOAP Encoding 的包内薄访问。 */
public final class SoapEncodedValueCodecAccess {
    private final SoapEncodedValueCodec encoding;

    private SoapEncodedValueCodecAccess(SoapVersion version) {
        this.encoding = new SoapEncodedValueCodec(version, SoapMessageCodec.newXmlMapper());
    }

    public static SoapEncodedValueCodecAccess soap11() {
        return new SoapEncodedValueCodecAccess(SoapVersion.SOAP_11);
    }

    public static SoapEncodedValueCodecAccess soap12() {
        return new SoapEncodedValueCodecAccess(SoapVersion.SOAP_12);
    }

    public Encoder encoder(Document document, Element body) {
        return new Encoder(this.encoding.encoder(document, body));
    }

    public Decoder decoder(Element body) {
        return new Decoder(this.encoding.decoder(body));
    }

    /** 编码上下文的测试侧薄包装。 */
    public static final class Encoder {
        private final SoapEncodedValueCodec.Encoder delegate;

        private Encoder(SoapEncodedValueCodec.Encoder delegate) {
            this.delegate = delegate;
        }

        public Element write(Element parent, QName name, QName xmlType, Type javaType, Object value) throws Exception {
            return this.delegate.write(parent, name, xmlType, javaType, value);
        }

        public void finish() throws Exception {
            this.delegate.finish();
        }
    }

    /** 解码上下文的测试侧薄包装。 */
    public static final class Decoder {
        private final SoapEncodedValueCodec.Decoder delegate;

        private Decoder(SoapEncodedValueCodec.Decoder delegate) {
            this.delegate = delegate;
        }

        public Object read(Element accessor, QName xmlType, Type javaType) throws Exception {
            return this.delegate.read(accessor, xmlType, javaType);
        }
    }
}
