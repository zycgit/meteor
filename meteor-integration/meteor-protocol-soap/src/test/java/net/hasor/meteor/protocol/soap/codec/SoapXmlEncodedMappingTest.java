/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.example.protocol.soap.MetXmlOrder;
import net.hasor.example.protocol.soap.MetXmlState;
import net.hasor.example.protocol.soap.codec.MetXmlEncodedValue;
import net.hasor.example.protocol.soap.codec.MetXmlLocalState;
import net.hasor.example.protocol.soap.codec.MetXmlUnnamedState;
import net.hasor.example.protocol.soap.codec.ResultMappingValues.Pair;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapXmlEncodedMappingTest {
    private static final String ENCODING_11   = "http://schemas.xmlsoap.org/soap/encoding/";
    private static final String XSI_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    @Test
    public void encodedRootMapIsRejectedBeforeItsContentsAreWritten() {
        // 动态 key 映射属于 literal，不能在 Encoding 中退化为一个空 struct。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);
        Type mapType = new TypeReference<Map<String, String>>() {
        }.getType();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, mapType, Map.of("timeout", "30")));
        assertTrue(failure.getMessage().contains("dynamic Map"));
    }

    @Test
    public void encodedNullMapIsRejectedBeforeTheNilShortcut() {
        // 不支持的 Map 类型即使值为空，也不能通过 nil 分支绕过协议边界。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);
        Type mapType = new TypeReference<Map<String, String>>() {
        }.getType();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, mapType, null));
        assertTrue(failure.getMessage().contains("dynamic Map"));
    }

    @Test
    public void encodedObjectValueCannotHideARuntimeMap() {
        // Object 声明不能让实际 Map 进入原有对象图编码。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, Object.class, Map.of("timeout", "30")));
        assertTrue(failure.getMessage().contains("dynamic Map"));
    }

    @Test
    public void encodedMapIsRejectedWhenReading() {
        // 即使 XML 没有图引用，声明的 Map 类型也只允许 literal 解码。
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        SoapXmlUtils.append(accessor, "", "timeout").setTextContent("30");
        Type mapType = new TypeReference<Map<String, String>>() {
        }.getType();
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_11).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, mapType));
        assertTrue(failure.getMessage().contains("dynamic Map"));
    }

    @Test
    public void encodedAttributeMapCannotBecomeAnEmptyStruct() {
        // 无包装的动态属性也不能悄悄跳过 Encoding 的类型限制。
        Element body = this.body(SoapVersion.SOAP_12);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_12).encoder(body.getOwnerDocument(), body);
        AttributeMapValue value = new AttributeMapValue();
        value.attributes = Map.of("timeout", "30");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, AttributeMapValue.class, value));
        assertTrue(failure.getMessage().contains("dynamic Map"));
    }

    @Test
    public void encodedCreatorOnlyValueIsRejectedWhenWriting() {
        // literal 可交给 Jackson 构造对象，encoded 仍需要先创建空对象再登记图身份。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);
        CreatorValue value = new CreatorValue("value");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, CreatorValue.class, value));
        assertTrue(failure.getMessage().contains("SOAP encoded POJOs require a no-argument constructor"));
    }

    @Test
    public void encodedCreatorOnlyValueIsRejectedWhenReading() {
        // 读取普通 struct 也不能绕开 creator，错误应说明 encoded 的映射边界。
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        SoapXmlUtils.append(accessor, "", "name").setTextContent("value");
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_11).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, CreatorValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded POJOs require a no-argument constructor"));
    }

    @Test
    public void encodedFactoryCreatorCannotBeBypassedByAnAvailableDefaultConstructor() {
        // 默认构造存在也不能替代显式工厂，否则 encoded 会跳过工厂中的字段转换。
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        SoapXmlUtils.append(accessor, "", "name").setTextContent("value");
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_11).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, FactoryValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded does not support Jackson factory creators"));
    }

    @Test
    public void encodedDisabledFactoryCreatorKeepsOrdinaryGraphBinding() throws Exception {
        // DISABLED 工厂不参与 Jackson 构造，仍按普通字段对象图往返。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);
        DisabledFactoryValue value = new DisabledFactoryValue();
        value.name = "value";

        Element accessor = encoder.write(body, new QName("value"), null, DisabledFactoryValue.class, value);
        encoder.finish();
        DisabledFactoryValue decoded = (DisabledFactoryValue) codec.decoder(body) //
                .read(accessor, null, DisabledFactoryValue.class);
        assertEquals("value", decoded.name);
    }

    @Test
    public void encodedNullCreatorValueCannotBypassMappingValidation() {
        // 空值不改变声明的构造要求，不能经 nil 分支接受不支持的 encoded 契约。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, CreatorValue.class, null));
        assertTrue(failure.getMessage().contains("SOAP encoded POJOs require a no-argument constructor"));
    }

    @Test
    public void encodedNilCreatorValueCannotBypassMappingValidation() {
        // 反序列化时同样在 nil 快捷返回之前验证声明类型。
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        accessor.setAttributeNS(XSI_NAMESPACE, "xsi:nil", "true");
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_11).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, CreatorValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded POJOs require a no-argument constructor"));
    }

    @Test
    public void encodedReadOnlyPropertyIsRejectedWhenWriting() {
        // JsonProperty 的单向属性不能在 encoded 中被当作双向字段使用。
        Element body = this.body(SoapVersion.SOAP_12);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_12).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, ReadOnlyValue.class, new ReadOnlyValue()));
        assertTrue(failure.getMessage().contains("SOAP encoded requires ordinary readable and writable properties"));
    }

    @Test
    public void encodedWriteOnlyPropertyIsRejectedWhenReading() {
        // 单向输入属性仍应在 encoded 绑定时明确拒绝，不在反射读写时出现空成员异常。
        Element body = this.body(SoapVersion.SOAP_12);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        SoapXmlUtils.append(accessor, "", "name").setTextContent("value");
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_12).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, WriteOnlyValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded requires ordinary readable and writable properties"));
    }

    @Test
    public void encodedAccessorTypeMismatchIsRejectedBeforeReflectiveAssignment() {
        // getter 与 setter 类型不同只能交由 Jackson 转换，encoded 不能把 String 直接传给 Integer setter。
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        SoapXmlUtils.append(accessor, "", "number").setTextContent("12");
        SoapEncodedValueCodec.Decoder decoder = this.codec(SoapVersion.SOAP_11).decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, MismatchedValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded requires ordinary readable and writable properties"));
    }

    @Test
    public void encodedJsonValueIsRejectedInsteadOfBecomingAnEmptyStruct() {
        // JsonValue 改变整个值的 XML 形态，encoded 不能忽略它并输出空对象图节点。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, CustomValue.class, new CustomValue()));
        assertTrue(failure.getMessage().contains("SOAP encoded does not support custom Jackson value bindings"));
    }

    @Test
    public void encodedRuntimeSubtypeCannotHideCustomJacksonBinding() {
        // 声明的父类可直接绑定，但实际子类的 JsonValue 仍必须进入相同检查。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_11).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, PlainValue.class, new CustomValue()));
        assertTrue(failure.getMessage().contains("SOAP encoded does not support custom Jackson value bindings"));
    }

    @Test
    public void encodedModuleSerializerIsRejectedWhenWriting() {
        // 同一个 XmlMapper 的 module 能改变 DTO 形态，encoded 不能静默跳过实际 serializer。
        XmlMapper mapper = SoapMessageCodec.newXmlMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(PlainValue.class, new JsonSerializer<PlainValue>() {
            @Override
            public void serialize(PlainValue value, JsonGenerator generator, SerializerProvider provider) //
                    throws IOException {
                generator.writeString(value.name);
            }
        });
        mapper.registerModule(module);
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = new SoapEncodedValueCodec(SoapVersion.SOAP_11, new SoapXmlMapping(mapper));
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, PlainValue.class, new PlainValue()));
        assertTrue(failure.getMessage().contains("SOAP encoded does not support custom Jackson value bindings"));
    }

    @Test
    public void encodedModuleDeserializerIsRejectedWhenReading() {
        // 输入绑定被 module 替换时，不能仍以反射字段写入来冒充这个 deserializer。
        XmlMapper mapper = SoapMessageCodec.newXmlMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(PlainValue.class, new JsonDeserializer<PlainValue>() {
            @Override
            public PlainValue deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                PlainValue value = new PlainValue();
                value.name = parser.getValueAsString();
                return value;
            }
        });
        mapper.registerModule(module);
        Element body = this.body(SoapVersion.SOAP_11);
        Element accessor = SoapXmlUtils.append(body, "", "value");
        accessor.setTextContent("value");
        SoapEncodedValueCodec codec = new SoapEncodedValueCodec(SoapVersion.SOAP_11, new SoapXmlMapping(mapper));
        SoapEncodedValueCodec.Decoder decoder = codec.decoder(body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                decoder.read(accessor, null, PlainValue.class));
        assertTrue(failure.getMessage().contains("SOAP encoded does not support custom Jackson value bindings"));
    }

    @Test
    public void encodedPropertySerializerIsRejectedWhenWriting() {
        // 属性级 serializer 也属于 Jackson 绑定，encoded 不能直接写原始成员值忽略它。
        Element body = this.body(SoapVersion.SOAP_12);
        SoapEncodedValueCodec.Encoder encoder = this.codec(SoapVersion.SOAP_12).encoder(body.getOwnerDocument(), body);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                encoder.write(body, new QName("value"), null, CustomPropertyValue.class, new CustomPropertyValue()));
        assertTrue(failure.getMessage().contains("SOAP encoded requires ordinary readable and writable properties"));
    }

    @Test
    public void encodedPropertiesFollowAscendingNumericOrder() throws Exception {
        // 图节点也使用成员 order，未标注的 middle 按默认 0 排列。
        Element body = this.body(SoapVersion.SOAP_11);
        SoapEncodedValueCodec codec = this.codec(SoapVersion.SOAP_11);
        NumericOrderValue value = new NumericOrderValue();
        value.last = "last";
        value.first = "first";
        value.middle = "middle";
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(body.getOwnerDocument(), body);

        // accessor 指向的实际图节点按数字升序输出子元素。
        encoder.write(body, new QName("value"), null, NumericOrderValue.class, value);
        encoder.finish();
        Element graph = (Element) body.getElementsByTagName("multiRef").item(0);
        assertEquals(3, graph.getChildNodes().getLength());
        assertEquals("first", graph.getChildNodes().item(0).getLocalName());
        assertEquals("middle", graph.getChildNodes().item(1).getLocalName());
        assertEquals("last", graph.getChildNodes().item(2).getLocalName());
    }

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
        return new SoapEncodedValueCodec(version, new SoapXmlMapping(SoapMessageCodec.newXmlMapper()));
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

    public static class AttributeMapValue {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> attributes;
    }

    public static class NumericOrderValue {
        @MetField
        @JsonProperty(index = 20)
        public String last;
        @MetField
        @JsonProperty(index = -10)
        public String first;
        public String middle;
    }

    public record CreatorValue(String name) {
        @JsonCreator
        public CreatorValue(@JsonProperty("name") String name) {
            this.name = name;
        }
    }

    public static class ReadOnlyValue {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String name = "value";
    }

    public static class FactoryValue {
        public String name;

        @JsonCreator
        public static FactoryValue create(@JsonProperty("name") String name) {
            FactoryValue value = new FactoryValue();
            value.name = name.toUpperCase(Locale.ROOT);
            return value;
        }
    }

    public static class DisabledFactoryValue {
        public String name;

        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public static DisabledFactoryValue create(@JsonProperty("name") String name) {
            throw new IllegalStateException("disabled factory must not be used");
        }
    }

    public static class WriteOnlyValue {
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String name;
    }

    public static class MismatchedValue {
        private Integer number;

        public String getNumber() {
            return this.number == null ? null : this.number.toString();
        }

        public void setNumber(Integer number) {
            this.number = number;
        }
    }

    public static class PlainValue {
        public String name = "value";
    }

    public static class CustomValue extends PlainValue {
        @JsonValue
        public String xmlValue() {
            return this.name;
        }
    }

    public static class CustomPropertyValue {
        @JsonSerialize(using = UppercaseSerializer.class)
        public String name = "value";
    }

    public static class UppercaseSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.toUpperCase(Locale.ROOT));
        }
    }
}
