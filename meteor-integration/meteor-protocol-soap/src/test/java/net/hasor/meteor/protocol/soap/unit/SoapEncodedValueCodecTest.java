/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.meteor.protocol.soap.codec.SoapEncodedValueCodecAccess;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapEncodedValueCodecTest {
    private static final String SOAP_11          = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP_11_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/";
    private static final String SOAP_12          = "http://www.w3.org/2003/05/soap-envelope";
    private static final String SOAP_12_ENCODING = "http://www.w3.org/2003/05/soap-encoding";
    private static final String XSI_NAMESPACE    = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String XSD_NAMESPACE    = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    @Test
    public void soap11ScalarCarriesItsXmlSchemaType() throws Exception {
        // 准备 SOAP 1.1 Body，并按 encoded 规则写入一个整数 accessor。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Element accessor = encoder.write(message.body(), new QName("value"), null, Integer.class, 17);
        encoder.finish();

        // 标量直接位于 accessor 中，并明确声明 XML Schema 类型。
        assertEquals("17", accessor.getTextContent());
        assertEquals(new QName(XSD_NAMESPACE, "int"), this.attributeQName(accessor, XSI_NAMESPACE, "type"));
        assertEquals(SOAP_11_ENCODING, accessor.getAttributeNS(SOAP_11, "encodingStyle"));
        assertEquals(17, access.decoder(message.body()).read(accessor, null, Integer.class));
    }

    @Test
    public void soap11NullUsesXsiNil() throws Exception {
        // 准备带声明类型的空值，使编码器保留 accessor 的契约信息。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        QName stringType = new QName(XSD_NAMESPACE, "string", "xsd");
        Element accessor = encoder.write(message.body(), new QName("value"), stringType, String.class, null);
        encoder.finish();

        // 空值使用 xsi:nil，反序列化后仍为 Java null。
        assertEquals("true", accessor.getAttributeNS(XSI_NAMESPACE, "nil"));
        assertEquals(stringType, this.attributeQName(accessor, XSI_NAMESPACE, "type"));
        assertNull(access.decoder(message.body()).read(accessor, stringType, String.class));
    }

    @Test
    public void soap11ArrayUsesHrefAndArrayType() throws Exception {
        // 准备 SOAP 1.1 数组，由 accessor 指向 Body 中的独立图节点。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Element accessor = encoder.write(message.body(), new QName("values"), null, String[].class, //
                new String[] { "first", "second" });
        encoder.finish();
        Element graphNode = this.graphNode(message.body());

        // SOAP 1.1 使用本地 href/id 和 enc:arrayType，并能恢复数组内容。
        assertEquals("#id1", accessor.getAttribute("href"));
        assertEquals("id1", graphNode.getAttribute("id"));
        assertEquals("xsd:string[2]", graphNode.getAttributeNS(SOAP_11_ENCODING, "arrayType"));
        Object decoded = access.decoder(message.body()).read(accessor, null, String[].class);
        assertArrayEquals(new String[] { "first", "second" }, (String[]) decoded);
    }

    @Test
    public void soap12ArrayUsesEncodingQualifiedGraphAttributes() throws Exception {
        // 准备 SOAP 1.2 数组，验证其专属的图和数组属性。
        Message message = this.message(SOAP_12);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap12();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Element accessor = encoder.write(message.body(), new QName("values"), null, Integer[].class, //
                new Integer[] { 3, 5 });
        encoder.finish();

        // SOAP 1.2 在 RPC 结构中内联首个图节点，数组维度使用 arraySize。
        assertEquals("id1", accessor.getAttributeNS(SOAP_12_ENCODING, "id"));
        assertEquals("array", accessor.getAttributeNS(SOAP_12_ENCODING, "nodeType"));
        assertEquals("xsd:int", accessor.getAttributeNS(SOAP_12_ENCODING, "itemType"));
        assertEquals("2", accessor.getAttributeNS(SOAP_12_ENCODING, "arraySize"));
        assertFalse(accessor.hasAttributeNS(XSI_NAMESPACE, "type"));
        Object decoded = access.decoder(message.body()).read(accessor, null, Integer[].class);
        assertArrayEquals(new Integer[] { 3, 5 }, (Integer[]) decoded);
    }

    @Test
    public void soap11MultidimensionalArrayUsesCommaSeparatedDimensions() throws Exception {
        // 准备一个矩形二维数组，其值按最后一维优先展平。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        int[][] expected = new int[][] { { 1, 2, 3 }, { 4, 5, 6 } };
        Element accessor = encoder.write(message.body(), new QName("values"), null, int[][].class, expected);
        encoder.finish();
        Element graphNode = this.graphNode(message.body());

        // SOAP 1.1 用逗号维度描述矩形数组，并恢复原始 Java 维度。
        assertEquals("xsd:int[2,3]", graphNode.getAttributeNS(SOAP_11_ENCODING, "arrayType"));
        int[][] decoded = (int[][]) access.decoder(message.body()).read(accessor, null, int[][].class);
        assertArrayEquals(expected[0], decoded[0]);
        assertArrayEquals(expected[1], decoded[1]);
    }

    @Test
    public void soap12MultidimensionalArrayUsesSpaceSeparatedDimensions() throws Exception {
        // 准备 SOAP 1.2 矩形二维数组。
        Message message = this.message(SOAP_12);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap12();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Integer[][] expected = new Integer[][] { { 1, 2 }, { 3, 4 } };
        Element accessor = encoder.write(message.body(), new QName("values"), null, Integer[][].class, expected);
        encoder.finish();

        // SOAP 1.2 arraySize 以空白分隔维度，最后一维变化最快。
        assertEquals("2 2", accessor.getAttributeNS(SOAP_12_ENCODING, "arraySize"));
        Integer[][] decoded = (Integer[][]) access.decoder(message.body()).read(accessor, null, Integer[][].class);
        assertArrayEquals(expected[0], decoded[0]);
        assertArrayEquals(expected[1], decoded[1]);
    }

    @Test
    public void soap11OffsetRestoresPartiallyTransmittedArray() throws Exception {
        // 构造从第三个位置开始传输的 SOAP 1.1 数组。
        Message message = this.message(SOAP_11);
        Element accessor = this.soap11Array(message, "xsd:int[5]");
        Element graphNode = this.graphNode(message.body());
        graphNode.setAttributeNS(SOAP_11_ENCODING, "enc:offset", "[2]");
        this.item(graphNode, "7");
        this.item(graphNode, "8");

        // offset 之前与未传输的位置保持 null。
        SoapEncodedValueCodecAccess.Decoder decoder = SoapEncodedValueCodecAccess.soap11().decoder(message.body());
        Integer[] decoded = (Integer[]) decoder.read(accessor, null, Integer[].class);
        assertArrayEquals(new Integer[] { null, null, 7, 8, null }, decoded);
    }

    @Test
    public void soap11PositionRestoresSparseArray() throws Exception {
        // 构造两个显式定位的 SOAP 1.1 稀疏数组成员。
        Message message = this.message(SOAP_11);
        Element accessor = this.soap11Array(message, "xsd:string[5]");
        Element graphNode = this.graphNode(message.body());
        Element first = this.item(graphNode, "first");
        first.setAttributeNS(SOAP_11_ENCODING, "enc:position", "[1]");
        Element second = this.item(graphNode, "last");
        second.setAttributeNS(SOAP_11_ENCODING, "enc:position", "[4]");

        // position 之间的空洞保留为 null。
        SoapEncodedValueCodecAccess.Decoder decoder = SoapEncodedValueCodecAccess.soap11().decoder(message.body());
        String[] decoded = (String[]) decoder.read(accessor, null, String[].class);
        assertArrayEquals(new String[] { null, "first", null, null, "last" }, decoded);
    }

    @Test
    public void soap12WildcardInfersTheFirstArrayDimension() throws Exception {
        // 构造首维未指定、次维固定为 2 的 SOAP 1.2 数组。
        Message message = this.message(SOAP_12);
        Element accessor = this.soap12Array(message, "xsd:int", "* 2");
        this.item(accessor, "1");
        this.item(accessor, "2");
        this.item(accessor, "3");
        this.item(accessor, "4");

        // 四个成员与固定次维推导出 2 x 2 结构。
        SoapEncodedValueCodecAccess.Decoder decoder = SoapEncodedValueCodecAccess.soap12().decoder(message.body());
        Integer[][] decoded = (Integer[][]) decoder.read(accessor, null, Integer[][].class);
        assertArrayEquals(new Integer[] { 1, 2 }, decoded[0]);
        assertArrayEquals(new Integer[] { 3, 4 }, decoded[1]);
    }

    @Test
    public void unknownEncodingStyleIsRejected() throws Exception {
        // 在 Body 上声明一个未知的 SOAP 1.2 编码方式。
        Message message = this.message(SOAP_12);
        message.body().setAttributeNS(SOAP_12, "soap:encodingStyle", "urn:test:unknown-encoding");

        // 未知编码无法安全还原值图，建立解码上下文时即失败。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapEncodedValueCodecAccess.soap12().decoder(message.body()));
        assertEquals("Unsupported SOAP encodingStyle: urn:test:unknown-encoding", failure.getMessage());
    }

    @Test
    public void encoderRejectsValueOutsideItsDeclaredJavaType() throws Exception {
        // 准备声明为 String 却传入 Integer 的 accessor。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess codec = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = codec.encoder(message.document(), message.body());

        // 值类型不能绕过 Java 服务契约。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> encoder.write(message.body(), new QName("value"), null, String.class, 1));
        assertTrue(failure.getMessage().contains("cannot be assigned to java.lang.String"));
    }

    @Test
    public void decoderRejectsArrayItemTypeOutsideItsDeclaredJavaType() throws Exception {
        // 构造声明 string 项类型的 SOAP 1.2 数组。
        Message message = this.message(SOAP_12);
        Element accessor = this.soap12Array(message, "xsd:string", "1");
        this.item(accessor, "not-an-integer");

        // 它不能按 Java Integer[] 契约解码。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                SoapEncodedValueCodecAccess.soap12().decoder(message.body()).read(accessor, null, Integer[].class));
        assertTrue(failure.getMessage().contains("cannot map to"));
    }

    @Test
    public void sharedDtoDecodesToOneObjectIdentity() throws Exception {
        // 同一个 DTO 从两个参数位置引用，只应产生一个独立图节点。
        Message message = this.message(SOAP_11);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap11();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Link shared = new Link();
        shared.setName("shared");
        QName linkType = new QName("urn:test:encoding", "Link", "tns");
        Element first = encoder.write(message.body(), new QName("first"), linkType, Link.class, shared);
        Element second = encoder.write(message.body(), new QName("second"), linkType, Link.class, shared);
        encoder.finish();

        // 两个 accessor 指向同一个 id，解码上下文也只构造一个 Java 对象。
        assertEquals(first.getAttribute("href"), second.getAttribute("href"));
        assertEquals(1, message.document().getElementsByTagName("multiRef").getLength());
        SoapEncodedValueCodecAccess.Decoder decoder = access.decoder(message.body());
        Link firstValue = (Link) decoder.read(first, linkType, Link.class);
        Link secondValue = (Link) decoder.read(second, linkType, Link.class);
        assertSame(firstValue, secondValue);
        assertEquals("shared", firstValue.getName());
    }

    @Test
    public void cyclicDtoRoundTripPreservesItsBackReference() throws Exception {
        // 构造指向自身的对象图，验证编码过程不会递归展开至溢出。
        Message message = this.message(SOAP_12);
        SoapEncodedValueCodecAccess access = SoapEncodedValueCodecAccess.soap12();
        SoapEncodedValueCodecAccess.Encoder encoder = access.encoder(message.document(), message.body());
        Link cycle = new Link();
        cycle.setName("cycle");
        cycle.setNext(cycle);
        QName linkType = new QName("urn:test:encoding", "Link", "tns");
        Element accessor = encoder.write(message.body(), new QName("value"), linkType, Link.class, cycle);
        encoder.finish();

        // 解码器先登记实例再读取属性，因此 next 仍指向对象自身。
        Link decoded = (Link) access.decoder(message.body()).read(accessor, linkType, Link.class);
        assertEquals("cycle", decoded.getName());
        assertSame(decoded, decoded.getNext());
    }

    @Test
    public void unresolvedReferenceIsRejectedBeforeValueDecoding() throws Exception {
        // 构造只含 href、没有对应 id 的 SOAP 1.1 Body。
        Message message = this.message(SOAP_11);
        Element accessor = message.document().createElement("value");
        accessor.setAttribute("href", "#missing");
        message.body().appendChild(accessor);

        // 建立解码上下文时立即拒绝悬空引用。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapEncodedValueCodecAccess.soap11().decoder(message.body()));
        assertEquals("SOAP Encoding reference does not resolve: missing", failure.getMessage());
    }

    @Test
    public void duplicateGraphIdIsRejectedBeforeValueDecoding() throws Exception {
        // 构造两个声明相同 enc:id 的 SOAP 1.2 图节点。
        Message message = this.message(SOAP_12);
        Element first = message.document().createElement("multiRef");
        first.setAttributeNS(SOAP_12_ENCODING, "enc:id", "same");
        message.body().appendChild(first);
        Element second = message.document().createElement("multiRef");
        second.setAttributeNS(SOAP_12_ENCODING, "enc:id", "same");
        message.body().appendChild(second);

        // 重复标识无法唯一还原对象图，必须在读取 part 之前失败。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapEncodedValueCodecAccess.soap12().decoder(message.body()));
        assertEquals("Duplicate SOAP Encoding id: same", failure.getMessage());
    }

    private Element soap11Array(Message message, String arrayType) {
        Element envelope = message.document().getDocumentElement();
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:enc", SOAP_11_ENCODING);
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD_NAMESPACE);
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NAMESPACE);

        Element accessor = message.document().createElement("values");
        accessor.setAttribute("href", "#array");
        message.body().appendChild(accessor);
        Element graphNode = message.document().createElement("multiRef");
        graphNode.setAttribute("id", "array");
        graphNode.setAttributeNS(XSI_NAMESPACE, "xsi:type", "enc:Array");
        graphNode.setAttributeNS(SOAP_11_ENCODING, "enc:arrayType", arrayType);
        message.body().appendChild(graphNode);
        return accessor;
    }

    private Element soap12Array(Message message, String itemType, String arraySize) {
        Element envelope = message.document().getDocumentElement();
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:enc", SOAP_12_ENCODING);
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD_NAMESPACE);

        Element accessor = message.document().createElement("values");
        accessor.setAttributeNS(SOAP_12_ENCODING, "enc:nodeType", "array");
        accessor.setAttributeNS(SOAP_12_ENCODING, "enc:itemType", itemType);
        accessor.setAttributeNS(SOAP_12_ENCODING, "enc:arraySize", arraySize);
        message.body().appendChild(accessor);
        return accessor;
    }

    private Element item(Element array, String value) {
        Element item = array.getOwnerDocument().createElement("item");
        item.setTextContent(value);
        array.appendChild(item);
        return item;
    }

    private Message message(String namespace) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();
        Element envelope = document.createElementNS(namespace, "soap:Envelope");
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:soap", namespace);
        document.appendChild(envelope);
        Element body = document.createElementNS(namespace, "soap:Body");
        envelope.appendChild(body);
        return new Message(document, body);
    }

    private Element graphNode(Element body) {
        return (Element) body.getElementsByTagName("multiRef").item(0);
    }

    private QName attributeQName(Element element, String namespace, String localName) {
        String value = element.getAttributeNS(namespace, localName);
        int separator = value.indexOf(':');
        if (separator < 0) {
            return new QName(value);
        }
        String prefix = value.substring(0, separator);
        return new QName(element.lookupNamespaceURI(prefix), value.substring(separator + 1));
    }

    /** 测试消息的 DOM 根和 Body。 */
    private static final class Message {
        private final Document document;
        private final Element  body;

        private Message(Document document, Element body) {
            this.document = document;
            this.body = body;
        }

        private Document document() {
            return this.document;
        }

        private Element body() {
            return this.body;
        }
    }

    /** 用于验证共享引用与循环引用的普通 DTO。 */
    public static class Link {
        private String name;
        private Link   next;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Link getNext() {
            return this.next;
        }

        public void setNext(Link next) {
            this.next = next;
        }
    }
}
