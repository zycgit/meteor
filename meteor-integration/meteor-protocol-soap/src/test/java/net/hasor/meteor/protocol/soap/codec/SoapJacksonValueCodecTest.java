/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

public class SoapJacksonValueCodecTest {
    @Test
    public void creatorConstructsWithoutDefaultConstructor() throws Exception {
        // 不可变 DTO 只有属性构造方法，读取必须由 Jackson 完成对象构建。
        Element source = this.target();
        SoapXmlUtils.append(source, "", "name").setTextContent("Alice");
        SoapXmlUtils.append(source, "", "age").setTextContent("30");

        CreatorValue result = (CreatorValue) this.codec().read(source, CreatorValue.class);
        assertEquals("Alice", result.name);
        assertEquals(30, result.age);
    }

    @Test
    public void contextualFieldSerializerRunsOnce() throws Exception {
        // 字段处理器需要所属属性上下文，且同一字段只能执行一次序列化。
        ContextualTextSerializer.CALLS.set(0);
        SerializedFieldValue value = new SerializedFieldValue();
        value.label = "Alice";
        Element target = this.target();

        this.codec().write(target, SerializedFieldValue.class, value, null);
        assertEquals("label:Alice", this.child(target, "label").getTextContent());
        assertEquals(1, ContextualTextSerializer.CALLS.get());
    }

    @Test
    public void contextualFieldDeserializerRunsOnce() throws Exception {
        // 输入字段处理器接收 BeanProperty，而不是被逐字段的二次转换重复调用。
        ContextualTextDeserializer.CALLS.set(0);
        Element source = this.target();
        SoapXmlUtils.append(source, "", "label").setTextContent("Alice");
        SoapLiteralValueCodec codec = this.codec();

        DeserializedFieldValue result = (DeserializedFieldValue) codec.read(source, DeserializedFieldValue.class);
        assertEquals("label:Alice", result.label);
        assertEquals(1, ContextualTextDeserializer.CALLS.get());
    }

    @Test
    public void moduleSerializerHandlesWholeDtoOnce() throws Exception {
        // 应用注册的模块可以接管整个 DTO，不能只作用于已经拆开的属性值。
        AtomicInteger calls = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(ModuleValue.class, new JsonSerializer<ModuleValue>() {
            @Override
            public void serialize(ModuleValue value, JsonGenerator generator, SerializerProvider provider) //
                    throws IOException {
                calls.incrementAndGet();
                generator.writeStartObject();
                generator.writeStringField("value", "module:" + value.value);
                generator.writeEndObject();
            }
        });
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(module);
        ModuleValue value = new ModuleValue();
        value.value = "Alice";
        Element target = this.target();

        // 写入经过模块处理后的属性值，同时检查没有重复执行整对象处理器。
        new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).write(target, ModuleValue.class, value, null);
        assertEquals("module:Alice", this.child(target, "value").getTextContent());
        assertEquals(1, calls.get());
    }

    @Test
    public void moduleDeserializerHandlesWholeDtoOnce() throws Exception {
        // 模块反序列化器收到整个 DTO 的属性结构，返回值不再交给第二次 Bean 绑定。
        AtomicInteger calls = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ModuleValue.class, new JsonDeserializer<ModuleValue>() {
            @Override
            public ModuleValue deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                calls.incrementAndGet();
                JsonNode node = parser.getCodec().readTree(parser);
                ModuleValue value = new ModuleValue();
                value.value = "module:" + node.get("value").asText();
                return value;
            }
        });
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(module);
        Element source = this.target();
        SoapXmlUtils.append(source, "", "value").setTextContent("Alice");

        // 读取结果必须直接来自已配置的 DTO 处理器。
        ModuleValue result = (ModuleValue) new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).read(source, ModuleValue.class);
        assertEquals("module:Alice", result.value);
        assertEquals(1, calls.get());
    }

    @Test
    public void nonNullInclusionOmitsNullElement() throws Exception {
        // JsonInclude 省略属性时不能由 SOAP 结构层重新补回 xsi:nil 元素。
        NonNullValue value = new NonNullValue();
        value.present = "Alice";
        Element target = this.target();

        this.codec().write(target, NonNullValue.class, value, null);
        assertEquals("Alice", this.child(target, "present").getTextContent());
        assertNull(this.child(target, "absent"));
    }

    @Test
    public void nonEmptyInclusionOmitsEmptyCollectionWrapper() throws Exception {
        // 空集合被 Jackson 省略后，原生 公共名称与 XML 配置 的外层包装也不应单独输出。
        NonEmptyValue value = new NonEmptyValue();
        value.labels = List.of();
        Element target = this.target();

        this.codec().write(target, NonEmptyValue.class, value, null);
        assertFalse(target.hasChildNodes());
    }

    @Test
    public void objectPropertyUsesDeclaredDeserializationType() throws Exception {
        // 字段声明 Object，但 Jackson 指定了具体输入类型，不能先退化为 DOM。
        Element source = this.target();
        Element value = SoapXmlUtils.append(source, "", "value");
        this.nativeValue(value);

        ConcreteObjectValue result = (ConcreteObjectValue) this.codec().read(source, ConcreteObjectValue.class);
        assertTrue(result.value instanceof NativeNamedValue);
        NativeNamedValue payload = (NativeNamedValue) result.value;
        assertEquals("123", payload.identifier);
        assertEquals("Alice", payload.name);
        assertEquals(List.of("first"), payload.tags);
    }

    @Test
    public void polymorphicDeclaredTypeWritesTypeId() throws Exception {
        // 已声明的 Jackson 多态类型在属性序列中输出标准的业务 discriminator。
        DogValue value = new DogValue();
        value.name = "Rex";
        value.age = 3;
        Element target = this.target();

        this.codec().write(target, AnimalValue.class, value, null);
        assertEquals("dog", target.getAttribute("kind"));
        assertEquals("Rex", this.child(target, "name").getTextContent());
        assertEquals("3", this.child(target, "age").getTextContent());
    }

    @Test
    public void polymorphicDeclaredTypeReadsSubtype() throws Exception {
        // JsonSubTypes 显式登记的子类型由 Jackson 根据输入 discriminator 选择。
        Element source = this.target();
        SoapXmlUtils.append(source, "", "kind").setTextContent("dog");
        SoapXmlUtils.append(source, "", "name").setTextContent("Rex");
        SoapXmlUtils.append(source, "", "age").setTextContent("3");

        Object result = this.codec().read(source, AnimalValue.class);
        assertTrue(result instanceof DogValue);
        assertEquals("Rex", ((DogValue) result).name);
        assertEquals(3, ((DogValue) result).age);
    }

    @Test
    public void polymorphicObjectPropertyWritesTypeId() throws Exception {
        // Object 字段上的多态声明依赖 BeanProperty 上下文，不能独立序列化后丢失。
        PolymorphicObjectValue value = new PolymorphicObjectValue();
        NamedValue payload = new NamedValue();
        payload.name = "Alice";
        value.value = payload;
        Element target = this.target();

        this.codec().write(target, PolymorphicObjectValue.class, value, null);
        Element output = this.child(target, "value");
        assertEquals("named", output.getAttribute("kind"));
        assertEquals("Alice", this.child(output, "name").getTextContent());
    }

    @Test
    public void polymorphicObjectPropertyReadsSubtype() throws Exception {
        // Object 字段有显式子类型声明时，应恢复为 DTO，而不是返回 DOM。
        Element source = this.target();
        Element value = SoapXmlUtils.append(source, "", "value");
        SoapXmlUtils.append(value, "", "kind").setTextContent("named");
        SoapXmlUtils.append(value, "", "name").setTextContent("Alice");
        SoapLiteralValueCodec codec = this.codec();

        PolymorphicObjectValue result = (PolymorphicObjectValue) codec.read(source, PolymorphicObjectValue.class);
        assertTrue(result.value instanceof NamedValue);
        assertEquals("Alice", ((NamedValue) result.value).name);
    }

    @Test
    public void aliasReadsLegacyElementName() throws Exception {
        // JsonAlias 提供输入兼容名称，原始 XML 名称必须保留到 Jackson 绑定阶段。
        Element source = this.target();
        SoapXmlUtils.append(source, "", "legacyName").setTextContent("Alice");

        AliasValue result = (AliasValue) this.codec().read(source, AliasValue.class);
        assertEquals("Alice", result.name);
    }

    @Test
    public void getterOnlyPropertyIsSerialized() throws Exception {
        // 只有 getter 的计算属性仍然可以输出，不要求存在对称的写入成员。
        Element target = this.target();

        this.codec().write(target, GetterOnlyValue.class, new GetterOnlyValue(), null);
        assertEquals("computed", this.child(target, "label").getTextContent());
    }

    @Test
    public void writeOnlyPropertyIsOmittedFromOutput() throws Exception {
        // WRITE_ONLY 即使存在 getter 也不能作为输出字段重新加入 XML。
        WriteOnlyValue value = new WriteOnlyValue();
        value.name = "Alice";
        value.setSecret("hidden");
        Element target = this.target();

        this.codec().write(target, WriteOnlyValue.class, value, null);
        assertEquals("Alice", this.child(target, "name").getTextContent());
        assertNull(this.child(target, "secret"));
    }

    @Test
    public void writeOnlyPropertyIsDeserialized() throws Exception {
        // WRITE_ONLY 字段仍然属于输入属性，不能因输出映射缺失而被丢弃。
        Element source = this.target();
        SoapXmlUtils.append(source, "", "secret").setTextContent("hidden");

        WriteOnlyValue result = (WriteOnlyValue) this.codec().read(source, WriteOnlyValue.class);
        assertEquals("hidden", result.getSecret());
    }

    @Test
    public void objectModuleDeserializerOverridesDomFallback() throws Exception {
        // 用户显式接管 Object 后，应收到 XML 对应的结构 token，而不是内嵌 DOM 对象。
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonToken> token = new AtomicReference<>();
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(this.objectModule(calls, token));
        Element source = this.target();
        SoapXmlUtils.append(source, "", "name").setTextContent("Alice");

        Object result = new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).read(source, Object.class);
        assertEquals("object:Alice", result);
        assertEquals(JsonToken.START_OBJECT, token.get());
        assertEquals(1, calls.get());
    }

    @Test
    public void objectPropertyUsesModuleDeserializer() throws Exception {
        // 嵌套 Object 同样沿用所属 mapper 的处理器，不能提前使用默认 DOM fallback。
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonToken> token = new AtomicReference<>();
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(this.objectModule(calls, token));
        Element source = this.target();
        Element value = SoapXmlUtils.append(source, "", "value");
        SoapXmlUtils.append(value, "", "name").setTextContent("Alice");

        ObjectValue result = (ObjectValue) new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).read(source, ObjectValue.class);
        assertEquals("object:Alice", result.value);
        assertEquals(JsonToken.START_OBJECT, token.get());
        assertEquals(1, calls.get());
    }

    @Test
    public void nativeAttributeUsesJacksonCreator() throws Exception {
        // XML 属性名与构造参数名不同，由 SOAP QName 映射后交给属性构造方法。
        Element source = this.target();
        source.setAttributeNS("urn:meta", "meta:id", "123");

        AttributeCreatorValue result = (AttributeCreatorValue) this.codec().read(source, AttributeCreatorValue.class);
        assertEquals("123", result.identifier);
    }

    @Test
    public void nativeAttributeWritesItsQNameAfterJacksonBinding() throws Exception {
        // Java 属性经过 Jackson 后仍要按 JacksonXmlProperty 的命名空间和名称写为 XML 属性。
        AttributeCreatorValue value = new AttributeCreatorValue("123");
        Element target = this.target();

        this.codec().write(target, AttributeCreatorValue.class, value, null);
        assertEquals("123", target.getAttributeNS("urn:meta", "id"));
        assertFalse(target.hasChildNodes());
    }

    @Test
    public void nativeElementWrapperSurvivesJacksonBinding() throws Exception {
        // 完整对象序列化只负责属性值，原生包装和每个条目的 QName 仍由 SOAP 映射控制。
        WrappedCreatorValue value = new WrappedCreatorValue(List.of("first", "second"));
        Element target = this.target();

        this.codec().write(target, WrappedCreatorValue.class, value, null);
        assertEquals(1, target.getChildNodes().getLength());
        Element wrapper = this.child(target, "labels");
        assertNotNull(wrapper);
        assertEquals(2, wrapper.getChildNodes().getLength());
        assertEquals("label", wrapper.getFirstChild().getLocalName());
        assertEquals("first", wrapper.getFirstChild().getTextContent());
        assertEquals("second", wrapper.getLastChild().getTextContent());
    }

    @Test
    public void nativeElementWrapperReadsIntoJacksonCreator() throws Exception {
        // 包装展开为一个 Java 集合属性后，Jackson 构造方法获得完整集合。
        Element source = this.target();
        Element wrapper = SoapXmlUtils.append(source, "", "labels");
        SoapXmlUtils.append(wrapper, "", "label").setTextContent("first");
        SoapXmlUtils.append(wrapper, "", "label").setTextContent("second");

        WrappedCreatorValue result = (WrappedCreatorValue) this.codec().read(source, WrappedCreatorValue.class);
        assertEquals(List.of("first", "second"), result.values);
    }

    @Test
    public void objectPropertyKeepsNativeShapeWithConcreteInputType() throws Exception {
        // Object 的运行期 DTO 与 as 指定的输入类型一致，原生属性、元素名和包装均须保留。
        ConcreteObjectValue value = new ConcreteObjectValue();
        value.value = this.nativeValue();
        Element target = this.target();

        this.codec().write(target, ConcreteObjectValue.class, value, null);
        Element output = this.child(target, "value");
        assertEquals("123", output.getAttributeNS("urn:meta", "id"));
        assertEquals("Alice", output.getElementsByTagNameNS("urn:profile", "display").item(0).getTextContent());
        assertEquals("first", this.child(this.child(output, "tags"), "tag").getTextContent());
    }

    @Test
    public void listContentSerializerKeepsNativeWrapper() throws Exception {
        // contentUsing 修改集合条目，集合的包装和条目 QName 仍来自 公共名称与 XML 配置。
        ContextualTextSerializer.CALLS.set(0);
        ContentListValue value = new ContentListValue();
        value.values = List.of("Alice");
        Element target = this.target();

        this.codec().write(target, ContentListValue.class, value, null);
        Element wrapper = (Element) target.getElementsByTagNameNS("urn:list", "entries").item(0);
        Element entry = (Element) wrapper.getFirstChild();
        assertEquals("entry", entry.getLocalName());
        assertEquals("urn:list", entry.getNamespaceURI());
        assertEquals("values:Alice", entry.getTextContent());
        assertEquals(1, ContextualTextSerializer.CALLS.get());
    }

    @Test
    public void listContentDeserializerKeepsNativeWrapper() throws Exception {
        // 输入先识别原生包装，再由 contentUsing 接收每个字符串条目。
        ContextualTextDeserializer.CALLS.set(0);
        Element source = this.target();
        Element wrapper = SoapXmlUtils.append(source, "urn:list", "entries");
        SoapXmlUtils.append(wrapper, "urn:list", "entry").setTextContent("Alice");

        ContentListValue result = (ContentListValue) this.codec().read(source, ContentListValue.class);
        assertEquals(List.of("values:Alice"), result.values);
        assertEquals(1, ContextualTextDeserializer.CALLS.get());
    }

    @Test
    public void listContentAsReadsNativeDtoShape() throws Exception {
        // List<Object> 的 contentAs 提供条目类型，同时保留条目 DTO 内部的原生映射。
        Element source = this.target();
        Element wrapper = SoapXmlUtils.append(source, "", "users");
        this.nativeValue(SoapXmlUtils.append(wrapper, "", "user"));

        ConcreteListValue result = (ConcreteListValue) this.codec().read(source, ConcreteListValue.class);
        NativeNamedValue payload = (NativeNamedValue) result.values.get(0);
        assertEquals("123", payload.identifier);
        assertEquals("Alice", payload.name);
        assertEquals(List.of("first"), payload.tags);
    }

    @Test
    public void listContentAsWritesNativeDtoShape() throws Exception {
        // 指定输入条目类型不影响输出中运行期 DTO 的原生属性和包装。
        ConcreteListValue value = new ConcreteListValue();
        value.values = List.of(this.nativeValue());
        Element target = this.target();

        this.codec().write(target, ConcreteListValue.class, value, null);
        Element output = this.child(this.child(target, "users"), "user");
        assertEquals("123", output.getAttributeNS("urn:meta", "id"));
        assertEquals("Alice", output.getElementsByTagNameNS("urn:profile", "display").item(0).getTextContent());
        assertEquals("first", this.child(this.child(output, "tags"), "tag").getTextContent());
    }

    @Test
    public void rootStringModuleSerializerRunsOnce() throws Exception {
        // 根字符串也必须使用用户模块，不能直接走内置标量文本转换。
        AtomicInteger calls = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new JsonSerializer<String>() {
            @Override
            public void serialize(String value, JsonGenerator generator, SerializerProvider provider) //
                    throws IOException {
                calls.incrementAndGet();
                generator.writeString("module:" + value);
            }
        });
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(module);
        Element target = this.target();

        // 一次编码只产生一次用户回调和一份转换后的文本。
        new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).write(target, String.class, "Alice", null);
        assertEquals("module:Alice", target.getTextContent());
        assertEquals(1, calls.get());
    }

    @Test
    public void rootStringModuleDeserializerRunsOnce() throws Exception {
        // 根标量读入也需要尊重模块反序列化器，不再二次调用转换器。
        AtomicInteger calls = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(String.class, new JsonDeserializer<String>() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                calls.incrementAndGet();
                return "module:" + parser.getValueAsString();
            }
        });
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.registerModule(module);
        Element source = this.target();
        source.setTextContent("Alice");

        // 结果来自用户处理器，且处理器恰好执行一次。
        assertEquals("module:Alice", new SoapLiteralValueCodec(new SoapXmlMapping(mapper)).read(source, String.class));
        assertEquals(1, calls.get());
    }

    @Test
    public void arrayFormattedBeanWritesOrderedItems() throws Exception {
        // JsonFormat ARRAY 产生数组形态，属性顺序由显式 JsonPropertyOrder 决定。
        ArrayValue value = new ArrayValue();
        value.name = "Alice";
        value.age = 30;
        Element target = this.target();

        this.codec().write(target, ArrayValue.class, value, null);
        assertEquals(2, target.getChildNodes().getLength());
        assertEquals("item", target.getFirstChild().getLocalName());
        assertEquals("Alice", target.getFirstChild().getTextContent());
        assertEquals("30", target.getLastChild().getTextContent());
    }

    @Test
    public void arrayFormattedBeanReadsOrderedItems() throws Exception {
        // XML 条目的位置形成 Jackson 数组，不能被重解释成普通 Bean 字段名。
        Element source = this.target();
        SoapXmlUtils.append(source, "", "item").setTextContent("Alice");
        SoapXmlUtils.append(source, "", "item").setTextContent("30");

        ArrayValue result = (ArrayValue) this.codec().read(source, ArrayValue.class);
        assertEquals("Alice", result.name);
        assertEquals(30, result.age);
    }

    @Test
    public void identityInfoRetainsSharedChildAfterRoundTrip() throws Exception {
        // 两个属性共享同一个实例，需要在一次完整绑定过程中保留对象标识表。
        IdentityChild child = new IdentityChild();
        child.id = "shared";
        child.name = "Alice";
        IdentityValue value = new IdentityValue();
        value.left = child;
        value.right = child;
        Element target = this.target();

        // 报文回读必须重新形成共享引用，而不是生成两个独立 DTO。
        SoapLiteralValueCodec codec = this.codec();
        codec.write(target, IdentityValue.class, value, null);
        IdentityValue result = (IdentityValue) codec.read(target, IdentityValue.class);
        assertSame(result.left, result.right);
        assertEquals("Alice", result.left.name);
    }

    @Test
    public void jacksonTextAndAttributeRetainTheirShapeAfterRoundTrip() throws Exception {
        // JacksonXmlText 与属性组合必须生成简单内容，而不是一个额外的 text 子元素。
        JacksonTextValue value = new JacksonTextValue();
        value.identifier = "123";
        value.text = "Alice";
        Element target = this.target();
        SoapLiteralValueCodec codec = this.codec();

        codec.write(target, JacksonTextValue.class, value, null);
        assertEquals("123", target.getAttribute("id"));
        assertEquals("Alice", target.getTextContent());
        assertEquals(0, target.getElementsByTagName("text").getLength());

        // 同一简单内容读取后恢复属性和文本两个独立 Java 字段。
        JacksonTextValue result = (JacksonTextValue) codec.read(target, JacksonTextValue.class);
        assertEquals("123", result.identifier);
        assertEquals("Alice", result.text);
    }

    @Test
    public void soapTextRejectsUnknownAttributeWithStrictMapper() {
        // 文本属性不能吞掉严格 mapper 要求拒绝的未知业务属性。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Element source = this.target();
        source.setAttribute("unexpected", "value");
        source.setTextContent("Alice");

        SoapLiteralValueCodec codec = new SoapLiteralValueCodec(new SoapXmlMapping(mapper));
        Exception failure = assertThrows(Exception.class, () -> codec.read(source, NativeTextValue.class));
        assertTrue(failure.getMessage().contains("unexpected"));
    }

    private NativeNamedValue nativeValue() {
        NativeNamedValue value = new NativeNamedValue();
        value.identifier = "123";
        value.name = "Alice";
        value.tags = List.of("first");
        return value;
    }

    private void nativeValue(Element target) {
        target.setAttributeNS("urn:meta", "meta:id", "123");
        SoapXmlUtils.append(target, "urn:profile", "display").setTextContent("Alice");
        Element wrapper = SoapXmlUtils.append(target, "", "tags");
        SoapXmlUtils.append(wrapper, "", "tag").setTextContent("first");
    }

    private SimpleModule objectModule(AtomicInteger calls, AtomicReference<JsonToken> token) {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Object.class, new JsonDeserializer<Object>() {
            @Override
            public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                calls.incrementAndGet();
                token.set(parser.currentToken());
                JsonNode node = parser.getCodec().readTree(parser);
                return "object:" + node.get("name").asText();
            }
        });
        return module;
    }

    private SoapLiteralValueCodec codec() {
        return new SoapLiteralValueCodec(new SoapXmlMapping(SoapXmlMapping.newMapper()));
    }

    private Element target() {
        Document document = SoapXmlUtils.newDocument();
        Element element = document.createElementNS("", "value");
        document.appendChild(element);
        return element;
    }

    private Element child(Element parent, String name) {
        return (Element) parent.getElementsByTagNameNS(null, name).item(0);
    }

    public record CreatorValue(String name, int age) {
        @JsonCreator
        public CreatorValue(@JsonProperty("name") String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }
    }

    public static class SerializedFieldValue {
        @JsonSerialize(using = ContextualTextSerializer.class)
        public String label;
    }

    public static class DeserializedFieldValue {
        @JsonDeserialize(using = ContextualTextDeserializer.class)
        public String label;
    }

    public static class ContextualTextSerializer extends JsonSerializer<String> implements ContextualSerializer {
        private static final AtomicInteger CALLS = new AtomicInteger();
        private              String        propertyName;

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) {
            ContextualTextSerializer serializer = new ContextualTextSerializer();
            serializer.propertyName = property.getName();
            return serializer;
        }

        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider provider) //
                throws IOException {
            CALLS.incrementAndGet();
            generator.writeString(this.propertyName + ":" + value);
        }
    }

    public static class ContextualTextDeserializer extends JsonDeserializer<String> implements ContextualDeserializer {
        private static final AtomicInteger CALLS = new AtomicInteger();
        private              String        propertyName;

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            ContextualTextDeserializer deserializer = new ContextualTextDeserializer();
            deserializer.propertyName = property.getName();
            return deserializer;
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            CALLS.incrementAndGet();
            return this.propertyName + ":" + parser.getValueAsString();
        }
    }

    public static class ModuleValue {
        public String value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NonNullValue {
        public String present;
        public String absent;
    }

    public static class NonEmptyValue {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @MetField(name = "label")
        @JacksonXmlElementWrapper(localName = "labels")
        public List<String> labels;
    }

    public static class ConcreteObjectValue {
        @JsonDeserialize(as = NativeNamedValue.class)
        public Object value;
    }

    public static class NativeNamedValue {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:meta")
        public String       identifier;
        @MetField(name = "display")
        @JacksonXmlProperty(namespace = "urn:profile")
        public String       name;
        @MetField(name = "tag")
        @JacksonXmlElementWrapper(localName = "tags")
        public List<String> tags;
    }

    public static class ContentListValue {
        @JsonSerialize(contentUsing = ContextualTextSerializer.class)
        @JsonDeserialize(contentUsing = ContextualTextDeserializer.class)
        @MetField(name = "entry")
        @JacksonXmlProperty(namespace = "urn:list")
        @JacksonXmlElementWrapper(localName = "entries", namespace = "urn:list")
        public List<String> values;
    }

    public static class ConcreteListValue {
        @JsonDeserialize(contentAs = NativeNamedValue.class)
        @MetField(name = "user")
        @JacksonXmlElementWrapper(localName = "users")
        public List<Object> values;
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({ "name", "age" })
    public static class ArrayValue {
        public String name;
        public int    age;
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    public static class IdentityChild {
        public String id;
        public String name;
    }

    public static class IdentityValue {
        public IdentityChild left;
        public IdentityChild right;
    }

    public static class JacksonTextValue {
        @JacksonXmlProperty(localName = "id", isAttribute = true)
        public String identifier;
        @JacksonXmlText
        public String text;
    }

    public static class NativeTextValue {
        @JacksonXmlText
        public String text;
    }

    @JsonSerialize(using = CustomContainerSerializer.class)
    public static class CustomContainer {
        public NativeNamedValue nested;
    }

    public static class CustomContainerSerializer extends JsonSerializer<CustomContainer> {
        @Override
        public void serialize(CustomContainer value, JsonGenerator generator, SerializerProvider provider) //
                throws IOException {
            generator.writeStartObject();
            generator.writeObjectField("nested", value.nested);
            generator.writeEndObject();
        }
    }

    public static class NamedValue {
        public String name;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = DogValue.class, name = "dog"))
    public abstract static class AnimalValue {
        public String name;
    }

    public static class DogValue extends AnimalValue {
        public int age;
    }

    public static class PolymorphicObjectValue {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonSubTypes(@JsonSubTypes.Type(value = NamedValue.class, name = "named"))
        public Object value;
    }

    public static class AliasValue {
        @JsonAlias("legacyName")
        public String name;
    }

    public static class GetterOnlyValue {
        public String getLabel() {
            return "computed";
        }
    }

    public static class WriteOnlyValue {
        public  String name;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String secret;

        public String getSecret() {
            return this.secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class ObjectValue {
        public Object value;
    }

    public record AttributeCreatorValue(@MetField(name = "id") @JacksonXmlProperty(isAttribute = true, namespace = "urn:meta") String identifier) {
        @JsonCreator
        public AttributeCreatorValue(@JsonProperty("identifier") String identifier) {
            this.identifier = identifier;
        }
    }

    public record WrappedCreatorValue(@MetField(name = "label") @JacksonXmlElementWrapper(localName = "labels") List<String> values) {
        @JsonCreator
        public WrappedCreatorValue(@JsonProperty("values") List<String> values) {
            this.values = values;
        }
    }
}
