/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.IOException;
import java.util.Collection;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 复用 Jackson 已配置的类型解析，仅为 SOAP XML 形状转换获取绑定类型，不创建 DTO。 */
final class SoapJacksonUtils {
    private SoapJacksonUtils() {
    }

    static JavaType resolvedType(XmlMapper mapper, Element element, JavaType declared, SoapXmlProperty property) //
            throws IOException {
        JavaType type = refinedType(mapper, declared, property);
        TypeDeserializer deserializer = typeDeserializer(mapper, declared, property);
        if (deserializer == null || !propertyTypeId(deserializer)) {
            return type;
        }
        String typeId = typeId(element, deserializer.getPropertyName());
        if (typeId != null) {
            DefaultDeserializationContext blueprint = //
                    (DefaultDeserializationContext) mapper.getDeserializationContext();
            DefaultDeserializationContext context = blueprint.createInstance( //
                    mapper.getDeserializationConfig(), null, mapper.getInjectableValues());
            JavaType resolved = deserializer.getTypeIdResolver().typeFromId(context, typeId);
            if (resolved != null) {
                // 与 Jackson TypeDeserializerBase 一致，保留声明泛型及自定义解析器返回的完整泛型。
                if (declared.getClass() == resolved.getClass() && !resolved.hasGenericTypes()) {
                    return context.constructSpecializedType(declared, resolved.getRawClass());
                }
                return resolved;
            }
        }
        Class<?> fallback = deserializer.getDefaultImpl();
        if (fallback != null && !ClassUtil.isBogusClass(fallback)) {
            return mapper.getTypeFactory().constructSpecializedType(declared, fallback);
        }
        // 缺失或未知 id 的最终处理仍由整体 Jackson reader 按用户配置决定。
        return type;
    }

    static String typeProperty(XmlMapper mapper, JavaType type, SoapXmlProperty property) throws JsonMappingException {
        TypeDeserializer deserializer = typeDeserializer(mapper, type, property);
        return deserializer != null && propertyTypeId(deserializer) ? deserializer.getPropertyName() : null;
    }

    static boolean arrayShape(XmlMapper mapper, JavaType type, SoapXmlProperty property) throws JsonMappingException {
        TypeDeserializer deserializer = typeDeserializer(mapper, type, property);
        if (deserializer != null && deserializer.getTypeInclusion() == JsonTypeInfo.As.WRAPPER_ARRAY) {
            return true;
        }
        JavaType refined = refinedType(mapper, type, property);
        DeserializationConfig config = mapper.getDeserializationConfig();
        JsonFormat.Value format = config.introspect(refined).findExpectedFormat();
        AnnotatedMember member = property == null ? null : property.getBindingMember();
        if (member != null) {
            format = format.withOverrides(config.getAnnotationIntrospector().findFormat(member));
        }
        if (format.getShape() == JsonFormat.Shape.ARRAY) {
            return true;
        }
        if (format.getShape() == JsonFormat.Shape.OBJECT) {
            return false;
        }
        return !SoapXmlMapping.isScalar(refined) && SoapXmlMapping.isCollection(refined);
    }

    private static boolean propertyTypeId(TypeDeserializer deserializer) {
        JsonTypeInfo.As inclusion = deserializer.getTypeInclusion();
        return inclusion == JsonTypeInfo.As.PROPERTY || inclusion == JsonTypeInfo.As.EXISTING_PROPERTY;
    }

    private static TypeDeserializer typeDeserializer(XmlMapper mapper, JavaType type, SoapXmlProperty property) //
            throws JsonMappingException {
        DeserializationConfig config = mapper.getDeserializationConfig();
        AnnotatedMember member = property == null ? null : property.getBindingMember();
        TypeDeserializer deserializer = type.getTypeHandler();
        boolean explicitResolver = false;
        if (deserializer == null && member != null) {
            AnnotationIntrospector introspector = config.getAnnotationIntrospector();
            boolean content = contentType(type, property);
            TypeResolverBuilder<?> resolver = content //
                    ? introspector.findPropertyContentTypeResolver(config, member, property.getJavaType()) //
                    : introspector.findPropertyTypeResolver(config, member, type);
            if (resolver != null) {
                explicitResolver = true;
                Collection<NamedType> subtypes = config.getSubtypeResolver() //
                        .collectAndResolveSubtypesByTypeId(config, member, type);
                deserializer = resolver.buildTypeDeserializer(config, type, subtypes);
            }
        }
        if (deserializer == null && !explicitResolver) {
            deserializer = config.findTypeDeserializer(type);
        }
        if (deserializer != null && member != null) {
            BeanProperty binding = new BeanProperty.Std(new PropertyName(property.getBindingName()), //
                    property.getJavaType(), null, member, PropertyMetadata.STD_OPTIONAL);
            deserializer = deserializer.forProperty(binding);
        }
        return deserializer;
    }

    private static JavaType refinedType(XmlMapper mapper, JavaType type, SoapXmlProperty property) //
            throws JsonMappingException {
        DeserializationConfig config = mapper.getDeserializationConfig();
        AnnotationIntrospector introspector = config.getAnnotationIntrospector();
        AnnotatedMember member = property == null ? null : property.getBindingMember();
        if (member != null) {
            if (contentType(type, property)) {
                type = introspector.refineDeserializationType(config, member, property.getJavaType()).getContentType();
            } else {
                type = introspector.refineDeserializationType(config, member, type);
            }
        }
        AnnotatedClass annotated = config.introspectClassAnnotations(type).getClassInfo();
        return introspector.refineDeserializationType(config, annotated, type);
    }

    private static boolean contentType(JavaType type, SoapXmlProperty property) {
        return property != null && property.getJavaType().hasContentType() //
                && type.equals(property.getJavaType().getContentType());
    }

    private static String typeId(Element element, String name) {
        // XmlMapper 的 PROPERTY 类型 id 通常是属性；同时接受原生读取器支持的子元素形式。
        if (element.hasAttribute(name)) {
            return element.getAttribute(name);
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && StringUtils.equals(name, child.getLocalName())) {
                return child.getTextContent();
            }
        }
        return null;
    }
}
