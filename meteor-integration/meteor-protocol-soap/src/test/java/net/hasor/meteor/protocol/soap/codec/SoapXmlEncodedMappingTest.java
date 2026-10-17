/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.example.protocol.soap.MetXmlOrder;
import net.hasor.example.protocol.soap.MetXmlState;
import net.hasor.example.protocol.soap.codec.MetXmlEncodedValue;
import net.hasor.example.protocol.soap.codec.MetXmlLocalState;
import net.hasor.example.protocol.soap.codec.MetXmlUnnamedState;
import net.hasor.example.protocol.soap.codec.ResultMappingValues.Pair;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapXmlEncodedMappingTest {
    private static final String ENCODING_11   = "http://schemas.xmlsoap.org/soap/encoding/";
    private static final String XSI_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    @Test
    public void encodedStructKeepsSameLocalNamesInSeparateNamespaces() throws Exception {
        // 两个成员具有相同 localName，Encoding 仍需要按完整 QName 区分它们。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.first = "first";
        value.second = "second";
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 对象仍使用独立的图节点，名称映射不改变 href/id 生命周期。
        Element accessor = encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, value);
        encoder.finish();
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);
        MetXmlEncodedValue decoded = (MetXmlEncodedValue) decoder.read(accessor, null, MetXmlEncodedValue.class);
        assertEquals("first", decoded.first);
        assertEquals("second", decoded.second);
        assertEquals(1, body.getElementsByTagNameNS("urn:meteor:xml:first", "value").getLength());
        assertEquals(1, body.getElementsByTagNameNS("urn:meteor:xml:second", "value").getLength());
    }

    @Test
    public void encodedBusinessAttributeKeepsItsQualifiedName() throws Exception {
        // 业务属性不占用 SOAP Encoding 保留的 id/href 名称。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.id = "business-9";
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 属性保存在实际图节点上，并由同一 QName 读取。
        Element accessor = encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, value);
        encoder.finish();
        Element graph = (Element) body.getElementsByTagName("multiRef").item(0);
        assertEquals("business-9", graph.getAttributeNS(MetXmlOrder.ATTRIBUTES, "identifier"));
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);
        MetXmlEncodedValue decoded = (MetXmlEncodedValue) decoder.read(accessor, null, MetXmlEncodedValue.class);
        assertEquals("business-9", decoded.id);
    }

    @Test
    public void encodedEnumMemberUsesItsNamedXmlTypeAndAlias() throws Exception {
        // 命名枚举类型位于独立 Schema 命名空间，字段不是普通 xsd:string 声明。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.state = MetXmlState.SENT;
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // wire 类型和字面值都与枚举映射一致。
        Element accessor = encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, value);
        encoder.finish();
        Element state = (Element) body.getElementsByTagName("state").item(0);
        assertEquals("already-sent", state.getTextContent());
        assertEquals(new QName(MetXmlOrder.TYPES, "OrderState"), this.type(state));
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);
        MetXmlEncodedValue decoded = (MetXmlEncodedValue) decoder.read(accessor, null, MetXmlEncodedValue.class);
        assertEquals(MetXmlState.SENT, decoded.state);
    }

    @Test
    public void encodedNamedStructUsesItsDeclaredXmlType() throws Exception {
        // 类型名称来自显式 Meteor 注解，不猜测生成器分配的 TypeN 名称。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 未显式传入顶层 XML 类型时，也能使用完全限定的命名类型。
        encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, new MetXmlEncodedValue());
        encoder.finish();
        Element graph = (Element) body.getElementsByTagName("multiRef").item(0);
        assertEquals(new QName("urn:meteor:xml:encoded", "EncodedValue"), this.type(graph));
    }

    @Test
    public void encodedCollectionRemainsASingleArrayAccessor() throws Exception {
        // Encoding 集合仍是数组图节点，不转换成 literal 的重复结构成员。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.entries = List.of("a", "b");
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 数组 accessor 只有一个，成员按原数组语义恢复。
        Element accessor = encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, value);
        encoder.finish();
        assertEquals(1, body.getElementsByTagName("entries").getLength());
        Element entries = (Element) body.getElementsByTagName("entries").item(0);
        assertTrue(entries.getAttribute("href").startsWith("#id"));
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);
        MetXmlEncodedValue decoded = (MetXmlEncodedValue) decoder.read(accessor, null, MetXmlEncodedValue.class);
        assertEquals(List.of("a", "b"), decoded.entries);
    }

    @Test
    public void encodedCircularReferenceRemainsTheSameObject() throws Exception {
        // Encoding 允许循环对象；新名称映射不能退化成 literal 的无限递归。
        Element body = this.body(SoapVersion.SOAP_12);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_12);
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.first = "root";
        value.next = value;
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // SOAP 1.2 首节点内联，后续成员通过 enc:ref 恢复同一实例。
        Element accessor = encoder.write(body, new QName("value"), null, MetXmlEncodedValue.class, value);
        encoder.finish();
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);
        MetXmlEncodedValue decoded = (MetXmlEncodedValue) decoder.read(accessor, null, MetXmlEncodedValue.class);
        assertEquals("root", decoded.first);
        assertSame(decoded, decoded.next);
    }

    @Test
    public void encodedEnumArrayUsesTheNamedItemType() throws Exception {
        // 枚举数组的成员类型需要保留其自定义 QName。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);
        MetXmlState[] value = { MetXmlState.READY, MetXmlState.SENT };

        // 数组描述和成员的 xsi:type 使用相同命名枚举类型。
        Element accessor = encoder.write(body, new QName("values"), null, MetXmlState[].class, value);
        encoder.finish();
        Element graph = (Element) body.getElementsByTagName("multiRef").item(0);
        String arrayType = graph.getAttributeNS(ENCODING_11, "arrayType");
        String prefix = arrayType.substring(0, arrayType.indexOf(':'));
        assertEquals(MetXmlOrder.TYPES, graph.lookupNamespaceURI(prefix));
        assertTrue(arrayType.endsWith(":OrderState[2]"));
        assertArrayEquals(value, (MetXmlState[]) codec.decoder(body).read(accessor, null, MetXmlState[].class));
    }

    @Test
    public void encodedEnumWithOnlyALocalTypeNameUsesItsElementDeclaration() throws Exception {
        // 枚举的类型命名空间由服务决定，值编码器不能用 string 基类覆盖其限制。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        Type type = new TypeReference<Pair<MetXmlLocalState>>() {
        }.getType();
        Pair<MetXmlLocalState> value = new Pair<>();
        value.setFirst(MetXmlLocalState.READY);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 没有多余的 xsi:type，Schema 按声明的 LocalState 限制验证该值。
        Element accessor = encoder.write(body, new QName("value"), null, type, value);
        encoder.finish();
        Element first = (Element) body.getElementsByTagName("first").item(0);
        assertFalse(first.hasAttributeNS(XSI_NAMESPACE, "type"));
        this.validateEnumField(first, "LocalState", "ready-to-send");
        Pair<?> decoded = (Pair<?>) codec.decoder(body).read(accessor, null, type);
        assertEquals(MetXmlLocalState.READY, decoded.getFirst());
    }

    @Test
    public void encodedUnnamedEnumUsesTheGeneratedElementDeclaration() throws Exception {
        // 没有类型注解的枚举由定义生成器分配 TypeN，wire 不猜测这个名称。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        Type type = new TypeReference<Pair<MetXmlUnnamedState>>() {
        }.getType();
        Pair<MetXmlUnnamedState> value = new Pair<>();
        value.setFirst(MetXmlUnnamedState.READY);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // 元素声明决定枚举类型，并保持原枚举字面值。
        Element accessor = encoder.write(body, new QName("value"), null, type, value);
        encoder.finish();
        Element first = (Element) body.getElementsByTagName("first").item(0);
        assertFalse(first.hasAttributeNS(XSI_NAMESPACE, "type"));
        this.validateEnumField(first, "Type2", "READY");
        Pair<?> decoded = (Pair<?>) codec.decoder(body).read(accessor, null, type);
        assertEquals(MetXmlUnnamedState.READY, decoded.getFirst());
    }

    private void validateEnumField(Element field, String typeName, String lexicalValue) throws Exception {
        String xsd = """
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <xsd:simpleType name="%s">
                    <xsd:restriction base="xsd:string">
                      <xsd:enumeration value="%s"/>
                    </xsd:restriction>
                  </xsd:simpleType>
                  <xsd:element name="first" type="%s"/>
                </xsd:schema>
                """.formatted(typeName, lexicalValue, typeName);
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.newSchema(new StreamSource(new StringReader(xsd))).newValidator().validate(new DOMSource(field));
    }

    private SoapEncodedValueCodec codec(SoapVersion version) {
        return new SoapEncodedValueCodec(version, SoapMessageCodec.newXmlMapper());
    }

    private Element body(SoapVersion version) {
        Document document = SoapXmlUtils.newDocument();
        Element envelope = document.createElementNS(version.namespace(), "soap:Envelope");
        document.appendChild(envelope);
        return SoapXmlUtils.append(envelope, version.namespace(), "soap:Body");
    }

    private QName type(Element element) {
        String name = element.getAttributeNS(XSI_NAMESPACE, "type");
        int separator = name.indexOf(':');
        String prefix = name.substring(0, separator);
        return new QName(element.lookupNamespaceURI(prefix), name.substring(separator + 1));
    }
}
