/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import org.w3c.dom.*;

/** 与生成 Schema 共用 Jackson XML 值映射，并保留真实二进制值的元素位置。 */
final class SoapLiteralValueCodec {
    private static final String    BINARY_VALUES = SoapLiteralValueCodec.class.getName() + ".binaryValues";
    private static final String    XSI_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private final        XmlMapper mapper;

    SoapLiteralValueCodec(XmlMapper mapper) {
        SimpleModule binaryModule = new SimpleModule();
        binaryModule.addSerializer(byte[].class, new BinarySerializer());
        this.mapper = mapper;
        this.mapper.registerModule(binaryModule);
    }

    Object read(Element element, Type type) throws Exception {
        String nil = element.getAttributeNS(XSI_NAMESPACE, "nil");
        if (StringUtils.equalsIgnoreCase(nil, "true") || StringUtils.equals(nil, "1")) {
            return null;
        }
        JavaType javaType = this.mapper.constructType(type);
        boolean scalarArray = javaType.hasRawClass(byte[].class) || javaType.hasRawClass(char[].class);
        if (scalarArray) {
            if (this.hasElement(element)) {
                throw new IllegalArgumentException("SOAP scalar array must contain only text");
            }
            return this.mapper.convertValue(element.getTextContent(), javaType);
        }
        boolean collection = javaType.isCollectionLikeType() || javaType.isArrayType();
        if (collection && !this.hasElement(element) && element.getTextContent().isBlank()) {
            return this.mapper.convertValue(Collections.emptyList(), javaType);
        }
        return this.mapper.readValue(this.bytes(element), javaType);
    }

    void write(Element target, Type type, Object value, Map<Element, byte[]> binaries) throws Exception {
        if (value == null) {
            target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NAMESPACE);
            target.setAttributeNS(XSI_NAMESPACE, "xsi:nil", "true");
            return;
        }

        // 序列化时捕获真正的 byte[]，避免再次调用 getter 或把普通 base64 字符串当作附件。
        BinaryValues captured = new BinaryValues();
        JavaType javaType = this.mapper.constructType(type);
        byte[] xml = this.mapper.writerFor(javaType) //
                .withAttribute(BINARY_VALUES, captured) //
                .writeValueAsBytes(value);
        Element source = this.parse(xml).getDocumentElement();
        NamedNodeMap attributes = source.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            if (!StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE, attribute.getName())) {
                target.setAttributeNS(attribute.getNamespaceURI(), attribute.getName(), attribute.getValue());
            }
        }
        Document document = target.getOwnerDocument();
        for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
            target.appendChild(document.importNode(child, true));
        }

        // 在最终 SOAP 文档内恢复正文，并将真正的二进制元素交给附件编码。
        captured.restore(target, binaries);
    }

    private boolean hasElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        SoapXmlUtils.validateDocument(document, "SOAP value");
        return document;
    }

    private byte[] bytes(Element element) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(element), new StreamResult(output));
        return output.toByteArray();
    }

    private static final class BinarySerializer extends JsonSerializer<byte[]> {
        @Override
        public void serialize(byte[] value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            BinaryValues captured = (BinaryValues) provider.getAttribute(BINARY_VALUES);
            if (captured == null) {
                generator.writeBinary(value);
            } else {
                generator.writeString(captured.capture(value));
            }
        }
    }

    private static final class BinaryValues {
        private final String              prefix = "meteor-binary-" + UUID.randomUUID() + "-";
        private final Map<String, byte[]> values = new HashMap<>();

        private String capture(byte[] value) {
            String marker = this.prefix + this.values.size();
            this.values.put(marker, value.clone());
            return marker;
        }

        private void restore(Element root, Map<Element, byte[]> binaries) {
            Deque<Element> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                Element element = pending.pop();
                this.restoreAttributes(element);
                boolean leaf = true;
                for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element child) {
                        leaf = false;
                        pending.push(child);
                    }
                }
                if (leaf) {
                    byte[] value = this.values.remove(element.getTextContent());
                    if (value != null) {
                        element.setTextContent(Base64.getEncoder().encodeToString(value));
                        if (binaries != null) {
                            binaries.put(element, value);
                        }
                    }
                }
            }
            if (!this.values.isEmpty()) {
                throw new IllegalStateException("Serialized SOAP binary value has no XML location");
            }
        }

        private void restoreAttributes(Element element) {
            // XML 属性仍保留内联 base64，MTOM 只替换元素内容。
            NamedNodeMap attributes = element.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                byte[] value = this.values.remove(attribute.getValue());
                if (value != null) {
                    attribute.setValue(Base64.getEncoder().encodeToString(value));
                }
            }
        }
    }
}
