/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SoapJacksonSchemaTest {
    private static final URI ENDPOINT = URI.create("http://localhost/services/JacksonService");

    @Test
    public void creatorKeepsTheDeclaredPropertySchema() throws Exception {
        // Jackson 属性构造器只改变对象创建，不改变固定 XML 字段。
        SoapGeneratedDefinitionResource resource = this.resource(CreatorService.class, SoapXmlMapping.newMapper());
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 原生字段命名进入契约，生成后即可验证请求。
        assertEquals("xsd:string", this.named(schema, "element", "wireMessage").getAttribute("type"));
        this.validate(resource, "<arg0><wireMessage>hello</wireMessage></arg0>");
    }

    @Test
    public void configuredSerializerUsesAnyTypeForTheDeclaredValue() throws Exception {
        // 用户 Module 可以改变整个 DTO 的 XML 内容，生成器使用同一 Mapper。
        XmlMapper template = XmlMapper.builder().build();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Message.class, new MessageSerializer());
        template.registerModule(module);
        SoapGeneratedDefinitionResource resource = this.resource(MessageService.class, //
                SoapXmlMapping.configuredMapper(template));
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 不根据旧 Bean 字段生成与自定义输出冲突的 sequence。
        assertEquals("xsd:anyType", this.named(schema, "element", "arg0").getAttribute("type"));
        this.validate(resource, "<arg0>custom-message</arg0>");
    }

    @Test
    public void nativePolymorphicTypeIdIsAllowedByTheFixedSchema() throws Exception {
        // Jackson 自己携带的类型标记是属性，不需要 Meteor 类型注册表。
        SoapGeneratedDefinitionResource resource = this.resource(PolymorphicService.class, SoapXmlMapping.newMapper());
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        assertEquals("xsd:anyType", this.named(schema, "element", "arg0").getAttribute("type"));
        this.validate(resource, "<arg0 kind=\"message\"><message>hello</message></arg0>");
    }

    @Test
    public void objectReadRefinementDoesNotChangeTheAnyTypeDeclaration() throws Exception {
        // JsonDeserialize.as 指定读取目标，Object 的公开契约仍为开放值。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapGeneratedDefinitionResource resource = this.resource(ConcreteObjectService.class, mapper);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        assertEquals("xsd:anyType", this.named(schema, "element", "value").getAttribute("type"));
        this.validate(resource, "<arg0><value><message>hello</message></value></arg0>");
    }

    @Test
    public void customAttributeValueUsesAnySimpleType() throws Exception {
        // 属性的自定义序列化器只输出文本，不把 Bean 字段作为 attribute 类型。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapGeneratedDefinitionResource resource = this.resource(CustomAttributeService.class, mapper);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        assertEquals("xsd:anySimpleType", this.named(schema, "attribute", "id").getAttribute("type"));
        this.validate(resource, "<arg0 id=\"custom-message\"/>");
    }

    @Test
    public void elementAliasIsAcceptedByTheFixedSchema() throws Exception {
        // 旧名称与规范名称表达同一个字段，固定 XSD 用 choice 接纳读取别名。
        SoapGeneratedDefinitionResource resource = this.resource(AliasedService.class, SoapXmlMapping.newMapper());
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        assertNotNull(this.named(schema, "element", "oldMessage"));
        this.validate(resource, "<arg0><oldMessage>hello</oldMessage></arg0>");
    }

    @Test
    public void attributeAliasDoesNotRequireTheCanonicalAttributeToo() throws Exception {
        // attribute 无 choice，别名可以独立承载值，业务必填约束交给绑定检查。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapGeneratedDefinitionResource resource = this.resource(AliasedAttributeService.class, mapper);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        assertEquals("", this.named(schema, "attribute", "id").getAttribute("use"));
        this.validate(resource, "<arg0 oldId=\"A\"/>");
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type, XmlMapper mapper) {
        ServiceDomain<?> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "JacksonService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        return new SoapGeneratedDefinitionResource(service, config, mapper);
    }

    private void validate(SoapGeneratedDefinitionResource resource, String parameters) throws Exception {
        String namespace = resource.definition().targetNamespace();
        String input = "<Body><s:echo xmlns:s=\"" + namespace + "\">" + parameters + "</s:echo></Body>";
        Element body = this.parse(input.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        resource.validateBody(body, resource.definition().operation("echo").inputBody());
    }

    private Element named(Document schema, String kind, String name) {
        NodeList elements = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, kind);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (StringUtils.equals(name, element.getAttribute("name"))) {
                return element;
            }
        }
        throw new AssertionError("Missing " + kind + ": " + name);
    }

    private Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private interface CreatorService {
        CreatedMessage echo(CreatedMessage value);
    }

    public record CreatedMessage(String message) {
        @JsonCreator
        public CreatedMessage(@JsonProperty("wireMessage") String message) {
            this.message = message;
        }

        @Override
        @JsonProperty("wireMessage")
        public String message() {
            return this.message;
        }
    }

    private interface MessageService {
        Message echo(Message value);
    }

    public static class Message {
        public String message;
    }

    private interface PolymorphicService {
        PolymorphicMessage echo(PolymorphicMessage value);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonTypeName("message")
    public static class PolymorphicMessage {
        public String message;
    }

    private interface ConcreteObjectService {
        ConcreteObject echo(ConcreteObject value);
    }

    public static class ConcreteObject {
        @JsonDeserialize(as = Message.class)
        public Object value;
    }

    private interface CustomAttributeService {
        CustomAttribute echo(CustomAttribute value);
    }

    public static class CustomAttribute {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true)
        @JsonSerialize(using = MessageSerializer.class)
        public Message message;
    }

    private interface AliasedService {
        AliasedMessage echo(AliasedMessage value);
    }

    public static class AliasedMessage {
        @JsonAlias("oldMessage")
        public String message;
    }

    private interface AliasedAttributeService {
        AliasedAttribute echo(AliasedAttribute value);
    }

    public static class AliasedAttribute {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(required = true)
        @JsonAlias("oldId")
        public String id;
    }

    public static class MessageSerializer extends JsonSerializer<Message> {
        @Override
        public void serialize(Message value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString("custom-message");
        }
    }
}
