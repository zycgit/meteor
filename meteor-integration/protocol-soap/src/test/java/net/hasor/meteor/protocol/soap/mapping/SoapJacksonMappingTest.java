/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SoapJacksonMappingTest {
    @Test
    public void configuredMapperCopiesModulesWithoutMutatingTheTemplate() throws Exception {
        // 用户通过原生 Module 配置序列化规则，SOAP 只持有其独立副本。
        XmlMapper template = XmlMapper.builder().build();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Message.class, new MessageSerializer());
        template.registerModule(module);
        AnnotationIntrospector original = template.getSerializationConfig().getAnnotationIntrospector();
        XmlMapper mapper = SoapXmlMapping.configuredMapper(template);

        // Mapper 副本使用真实自定义序列化器，模板的注解处理器没有被替换。
        assertNotSame(template, mapper);
        assertSame(original, template.getSerializationConfig().getAnnotationIntrospector());
        assertTrue(mapper.writeValueAsString(new Message()).contains("custom-message"));
        assertTrue(new SoapXmlMapping(mapper).type(mapper.constructType(Message.class)).isJacksonValue());
    }

    @Test
    public void configuredMapperKeepsNativeMixinNames() {
        // Mix-in 属于 Jackson 配置，不需要在 DTO 上复制注解。
        XmlMapper template = XmlMapper.builder().build();
        template.addMixIn(Message.class, MessageMixin.class);
        XmlMapper mapper = SoapXmlMapping.configuredMapper(template);
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(Message.class)) //
                .getProperties().get(0);

        // token 绑定名称与 XML QName 都采用生效后的 Jackson 名称。
        assertEquals("wireMessage", property.getBindingName());
        assertEquals(new QName("wireMessage"), property.getName());
        assertEquals("message", property.getJavaName());
    }

    @Test
    public void meteorNameStillOverridesNativeJsonName() {
        // 显式 SOAP QName 优先，而 Jackson token 使用稳定 Java 属性名。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(MeteorMessage.class)) //
                .getProperties().get(0);

        assertEquals("message", property.getBindingName());
        assertEquals(new QName("urn:message", "text"), property.getName());
    }

    @Test
    public void propertyCreatorDoesNotRequireADefaultConstructor() {
        // immutable DTO 通过 Jackson 属性构造器创建，不要求手工字段赋值。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlType type = new SoapXmlMapping(mapper).type(mapper.constructType(CreatedMessage.class));

        // 固定字段仍能进入 XSD，不因为 creator 而退化为任意结构。
        assertFalse(type.isJacksonValue());
        assertFalse(type.hasDefaultConstructor());
        assertEquals("message", type.getProperties().get(0).getBindingName());
        assertNotNull(type.getProperties().get(0).getBindingMember());
    }

    @Test
    public void meteorFieldCanBindToAJacksonPropertyCreator() throws Exception {
        // SOAP XML 名称与 Jackson 构造器参数名称分别保留，不以 XML 名称查 Java 参数。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(MeteorCreatedMessage.class)) //
                .getProperties().get(0);

        assertEquals("message", property.getBindingName());
        assertEquals(new QName("text"), property.getName());
        MeteorCreatedMessage value = mapper.readValue("<value><message>hello</message></value>", //
                MeteorCreatedMessage.class);
        assertEquals("hello", value.message());
    }

    @Test
    public void nativeAliasesAreAvailableToXmlBinding() {
        // 别名只影响读取，序列化仍使用规范名称。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(AliasedMessage.class)) //
                .getProperties().get(0);

        assertEquals("message", property.getBindingName());
        assertTrue(property.getAliases().contains("oldMessage"));
    }

    @Test
    public void moduleDeserializerIsRecognizedWithoutClassAnnotations() {
        // 自定义反序列化器来自 Module，不能只靠扫描类上的注解来判断。
        XmlMapper template = XmlMapper.builder().build();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Message.class, new MessageDeserializer());
        template.registerModule(module);
        XmlMapper mapper = SoapXmlMapping.configuredMapper(template);

        assertTrue(new SoapXmlMapping(mapper).type(mapper.constructType(Message.class)).isJacksonValue());
    }

    @Test
    public void objectPropertyCanDeclareItsJacksonReadType() {
        // Object 的具体读取目标由 Jackson 原生 as 配置决定。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(ConcreteObject.class)) //
                .getProperties().get(0);

        assertEquals(Object.class, property.getJavaType().getRawClass());
        assertTrue(property.isJacksonValue());
    }

    @Test
    public void propertyPolymorphismUsesJacksonValueBinding() {
        // 多态标记归属属性上下文，不能仅检查 Object 类本身。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(PolymorphicObject.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonValue());
        assertNotNull(property.getBindingMember().getAnnotation(JsonTypeInfo.class));
    }

    @Test
    public void polymorphicDtoRetainsItsKnownSoapProperties() {
        // 类型标记扩展内容，但不能抹掉业务字段的 公共名称与 XML 配置 映射。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlType type = new SoapXmlMapping(mapper).type(mapper.constructType(PolymorphicMessage.class));

        assertTrue(type.isJacksonValue());
        assertEquals(new QName("text"), type.getProperties().get(0).getName());
    }

    @Test
    public void unwrappedPropertiesMakeTheOwningShapeOpen() {
        // JsonUnwrapped 改变父节点的字段集合，由 Jackson 决定完整结构。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlType type = new SoapXmlMapping(mapper).type(mapper.constructType(UnwrappedMessage.class));

        assertTrue(type.isJacksonValue());
    }

    @Test
    public void formattedScalarUsesItsNativeJacksonShape() {
        // Date 的文本格式不能继续按 Meteor 原来的 long 词法解释。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(FormattedDate.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonValue());
    }

    @Test
    public void datePatternWithoutExplicitShapeStillUsesJacksonBinding() {
        // pattern 本身已经把日期改为文本，不能要求用户重复写 shape=STRING。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(PatternDate.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonValue());
    }

    @Test
    public void configuredDateFormatIsVisibleToSchemaClassification() {
        // 用户通过 Mapper 设置日期格式，没有在 DTO 上增加注解。
        XmlMapper template = XmlMapper.builder().build();
        template.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));
        XmlMapper mapper = SoapXmlMapping.configuredMapper(template);

        assertTrue(new SoapXmlMapping(mapper).isJacksonValue(mapper.constructType(Date.class)));
    }

    @Test
    public void collectionContentSerializerKeepsItsItemBinding() {
        // contentUsing 改变每个条目，不改变 SOAP 集合外层的重复元素。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(CustomContent.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonValue());
        assertTrue(property.isJacksonContent());
    }

    @Test
    public void collectionContentReadTypeKeepsItsItemBinding() {
        // contentAs 仅缩小元素类型，不把 List<Object> 变成单一 DTO 值。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(ConcreteContent.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonContent());
    }

    @Test
    public void wholeCollectionSerializerDoesNotBecomeAnItemSerializer() {
        // using 控制整个集合的表示，不按 contentUsing 的规则逐项处理。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(CustomCollection.class)) //
                .getProperties().get(0);

        assertTrue(property.isJacksonValue());
        assertFalse(property.isJacksonContent());
    }

    @Test
    public void meteorEnumNamesAreUsedByJacksonItself() throws Exception {
        // 整 DTO 绑定时枚举序列化与反序列化也必须识别 SOAP 字面量。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        String xml = mapper.writeValueAsString(MeteorState.READY);

        assertTrue(xml.contains("ready to send"));
        assertEquals(MeteorState.READY, mapper.readValue(xml, MeteorState.class));
    }

    @Test
    public void readOnlyNativePropertyRetainsItsOutputMapping() {
        // 单向属性允许 Jackson 控制方向，不再强迫补造一个 setter。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = new SoapXmlMapping(mapper).type(mapper.constructType(ReadOnlyMessage.class)) //
                .getProperties().get(0);

        assertTrue(property.isReadable());
        assertFalse(property.isWritable());
        assertEquals("message", property.getBindingName());
    }

    public static class Message {
        public String message;
    }

    public abstract static class MessageMixin {
        @JsonProperty("wireMessage")
        public String message;
    }

    public static class MeteorMessage {
        @JsonProperty("nativeMessage")
        @MetField(name = "text")
        @JacksonXmlProperty(namespace = "urn:message")
        public String message;
    }

    public record CreatedMessage(String message) {
        @JsonCreator
        public CreatedMessage(@JsonProperty("message") String message) {
            this.message = message;
        }
    }

    public static class AliasedMessage {
        @JsonAlias("oldMessage")
        public String message;
    }

    public record MeteorCreatedMessage(@MetField(name = "text") String message) {
        @JsonCreator
        public MeteorCreatedMessage(@JsonProperty("message") String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return this.message;
        }
    }

    public static class ConcreteObject {
        @JsonDeserialize(as = Message.class)
        public Object value;
    }

    public static class PolymorphicObject {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes(@JsonSubTypes.Type(value = Message.class, name = "message"))
        public Object value;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    public static class PolymorphicMessage {
        @MetField(name = "text")
        public String message;
    }

    public static class UnwrappedMessage {
        @JsonUnwrapped
        public Message value;
    }

    public static class FormattedDate {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        public Date date;
    }

    public static class PatternDate {
        @JsonFormat(pattern = "yyyy-MM-dd")
        public Date date;
    }

    public static class CustomContent {
        @JsonSerialize(contentUsing = MessageSerializer.class)
        public List<Message> messages;
    }

    public static class ConcreteContent {
        @JsonDeserialize(contentAs = Message.class)
        public List<Object> messages;
    }

    public static class CustomCollection {
        @JsonSerialize(using = MessageListSerializer.class)
        public List<Message> messages;
    }

    public static class ReadOnlyMessage {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String getMessage() {
            return "message";
        }
    }

    public enum MeteorState {
        @MetEnumValue(name = "ready to send") READY
    }

    public static class MessageSerializer extends JsonSerializer<Message> {
        @Override
        public void serialize(Message value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString("custom-message");
        }
    }

    public static class MessageDeserializer extends JsonDeserializer<Message> {
        @Override
        public Message deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Message value = new Message();
            value.message = parser.getValueAsString();
            return value;
        }
    }

    public static class MessageListSerializer extends JsonSerializer<List<Message>> {
        @Override
        public void serialize(List<Message> value, JsonGenerator generator, // 整个集合输出一个自定义值
                SerializerProvider provider) throws IOException {
            generator.writeString("custom-list");
        }
    }
}
