/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapJacksonUtilsTest {
    @Test
    public void propertyTypeIdUsesJacksonDeclaredSubtypeNames() throws Exception {
        // DTO 类型只在 Jackson 注解中声明，SOAP 没有自己的名字到类型映射。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, NamedHolder.class);
        Element value = this.value();
        value.setAttribute("kind", "user");

        JavaType resolved = SoapJacksonUtils.resolvedType(mapper, value, property.getJavaType(), property);
        assertEquals(User.class, resolved.getRawClass());
        assertEquals("kind", SoapJacksonUtils.typeProperty(mapper, property.getJavaType(), property));
    }

    @Test
    public void classTypeIdUsesJacksonDeclaredSubtypeNames() throws Exception {
        // 类级多态规则同样用于定位需要恢复的 SOAP 字段结构。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        JavaType type = mapper.constructType(Animal.class);
        Element value = this.value();
        SoapXmlUtils.append(value, "", "kind").setTextContent("cat");

        assertEquals(Cat.class, SoapJacksonUtils.resolvedType(mapper, value, type, null).getRawClass());
    }

    @Test
    public void propertyDeserializeAsSelectsTheJacksonTargetType() throws Exception {
        // Object 的 as 配置可确定 DTO 形状，无需在报文中额外声明类型。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, RefinedHolder.class);

        JavaType type = SoapJacksonUtils.resolvedType(mapper, this.value(), property.getJavaType(), property);
        assertEquals(User.class, type.getRawClass());
    }

    @Test
    public void collectionContentAsSelectsTheJacksonItemType() throws Exception {
        // 容器属性的 contentAs 作用于条目，不错误地应用为整个 List 的实现类。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, RefinedListHolder.class);
        JavaType content = property.getJavaType().getContentType();

        assertEquals(User.class, SoapJacksonUtils.resolvedType(mapper, this.value(), content, property).getRawClass());
    }

    @Test
    public void collectionTypeInfoSelectsTheJacksonItemSubtype() throws Exception {
        // 集合属性上的类型注解遵循 Jackson 规则，只描述集合中的 Object 条目。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, NamedListHolder.class);
        JavaType content = property.getJavaType().getContentType();
        Element value = this.value();
        value.setAttribute("kind", "user");

        assertEquals(User.class, SoapJacksonUtils.resolvedType(mapper, value, content, property).getRawClass());
    }

    @Test
    public void customTypeResolverReceivesTheMapperContextAttributes() throws Exception {
        // 原生解析器可以使用用户 mapper 的上下文配置，不被 SOAP 的别名逻辑替代。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        mapper.setConfig(mapper.getDeserializationConfig().withAttribute("user-type-enabled", Boolean.TRUE));
        SoapXmlProperty property = this.property(mapper, CustomHolder.class);
        Element value = this.value();
        value.setAttribute("kind", "configured-user");

        JavaType resolved = SoapJacksonUtils.resolvedType(mapper, value, property.getJavaType(), property);
        assertEquals(User.class, resolved.getRawClass());
    }

    @Test
    public void unknownNamedTypeIsLeftForTheJacksonReaderToReject() throws Exception {
        // 未知名称不能猜测 Java 类；最终错误仍遵循原生 Jackson 的多态规则。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, NamedHolder.class);
        Element value = this.value();
        value.setAttribute("kind", "missing");

        JavaType resolved = SoapJacksonUtils.resolvedType(mapper, value, property.getJavaType(), property);
        assertEquals(Object.class, resolved.getRawClass());
        assertThrows(InvalidTypeIdException.class, //
                () -> mapper.readValue("<holder><value kind='missing'/></holder>", NamedHolder.class));
    }

    @Test
    public void propertyTypeInfoNoneOverridesTheClassTypeInfo() throws Exception {
        // 显式禁用属性多态时不能又退回到类级类型规则。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, PlainAnimalHolder.class);
        Element value = this.value();
        value.setAttribute("kind", "cat");

        JavaType resolved = SoapJacksonUtils.resolvedType(mapper, value, property.getJavaType(), property);
        assertEquals(Animal.class, resolved.getRawClass());
        assertNull(SoapJacksonUtils.typeProperty(mapper, property.getJavaType(), property));
    }

    @Test
    public void defaultTypingPreservesTheConfiguredSubtypeValidator() throws Exception {
        // SOAP 仅调用 Jackson 解析器，不能绕过用户为 CLASS id 配置的类型校验。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder() //
                .allowIfSubType(User.class).build();
        mapper.activateDefaultTypingAsProperty(validator, XmlMapper.DefaultTyping.NON_FINAL, "kind");
        JavaType type = mapper.constructType(Object.class);
        Element value = this.value();
        value.setAttribute(SoapJacksonUtils.typeProperty(mapper, type, null), "java.io.File");

        assertThrows(InvalidTypeIdException.class, () -> SoapJacksonUtils.resolvedType(mapper, value, type, null));
    }

    @Test
    public void classArrayFormatPreservesTheJacksonTupleShape() throws Exception {
        // 类级 ARRAY 形状的重复 item 必须交给 Jackson 作为数组，不能变为普通属性。
        XmlMapper mapper = SoapXmlMapping.newMapper();

        assertTrue(SoapJacksonUtils.arrayShape(mapper, mapper.constructType(Tuple.class), null));
    }

    @Test
    public void propertyArrayFormatOverridesTheClassShape() throws Exception {
        // 属性上的格式配置同样参与 XML 条目的静态形状判断。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, TupleHolder.class);

        assertTrue(SoapJacksonUtils.arrayShape(mapper, property.getJavaType(), property));
    }

    @Test
    public void wrapperArrayTypeInfoPreservesTheTypeIdAndValueSequence() throws Exception {
        // WRAPPER_ARRAY 的首项是类型 id，不能把它和 DTO 内容合并成 item 字段。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, WrappedHolder.class);

        assertTrue(SoapJacksonUtils.arrayShape(mapper, property.getJavaType(), property));
    }

    @Test
    public void deserializeAsCollectionSelectsAnArrayShape() throws Exception {
        // Object 经原生 as 具体化为集合后，空集合和重复条目按数组交给 reader。
        XmlMapper mapper = SoapXmlMapping.newMapper();
        SoapXmlProperty property = this.property(mapper, CollectionHolder.class);

        assertTrue(SoapJacksonUtils.arrayShape(mapper, property.getJavaType(), property));
    }

    @Test
    public void binaryArraysKeepTheirScalarShape() throws Exception {
        // byte[] 的 XML 基础类型仍是 base64Binary，不作为重复 item 集合。
        XmlMapper mapper = SoapXmlMapping.newMapper();

        assertFalse(SoapJacksonUtils.arrayShape(mapper, mapper.constructType(byte[].class), null));
    }

    private SoapXmlProperty property(XmlMapper mapper, Class<?> owner) {
        return new SoapXmlMapping(mapper).type(mapper.constructType(owner)).getProperties().get(0);
    }

    private Element value() {
        Document document = SoapXmlUtils.newDocument();
        Element value = document.createElementNS("", "value");
        document.appendChild(value);
        return value;
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public static class Tuple {
        public String name;
    }

    public static class TupleHolder {
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        public User value;
    }

    public static class WrappedHolder {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
        @JsonSubTypes(@JsonSubTypes.Type(value = User.class, name = "user"))
        public Object value;
    }

    public static class CollectionHolder {
        @JsonDeserialize(as = ArrayList.class)
        public Object value;
    }

    public static class User {
        public String name;
    }

    public static class NamedHolder {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonSubTypes(@JsonSubTypes.Type(value = User.class, name = "user"))
        public Object value;
    }

    public static class RefinedHolder {
        @JsonDeserialize(as = User.class)
        public Object value;
    }

    public static class RefinedListHolder {
        @JsonDeserialize(contentAs = User.class)
        public List<Object> value;
    }

    public static class NamedListHolder {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonSubTypes(@JsonSubTypes.Type(value = User.class, name = "user"))
        public List<Object> value;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = Cat.class, name = "cat"))
    public static class Animal {
        public String name;
    }

    public static class Cat extends Animal {
    }

    public static class PlainAnimalHolder {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
        public Animal value;
    }

    public static class CustomHolder {
        @JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonTypeIdResolver(ConfiguredTypeResolver.class)
        public Object value;
    }

    public static class ConfiguredTypeResolver extends TypeIdResolverBase {
        @Override
        public String idFromValue(Object value) {
            return "configured-user";
        }

        @Override
        public String idFromValueAndType(Object value, Class<?> type) {
            return "configured-user";
        }

        @Override
        public JavaType typeFromId(DatabindContext context, String id) {
            boolean enabled = Boolean.TRUE.equals(context.getAttribute("user-type-enabled"));
            return enabled && StringUtils.equals("configured-user", id) ? context.constructType(User.class) : null;
        }

        @Override
        public JsonTypeInfo.Id getMechanism() {
            return JsonTypeInfo.Id.CUSTOM;
        }
    }
}
