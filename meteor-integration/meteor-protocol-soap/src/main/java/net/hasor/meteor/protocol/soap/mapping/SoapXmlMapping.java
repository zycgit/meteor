/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.stream.BaseStream;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlAnnotationIntrospector;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.annotation.*;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;

/** 定义生成与 XML 编解码共享的映射编译器，QName 与 Java 属性名称相互独立。 */
public final class SoapXmlMapping {
    private static final Set<String>                SUPPORTED_ANNOTATIONS;
    private final        XmlMapper                  mapper;
    private final        Map<JavaType, SoapXmlType> types = new ConcurrentHashMap<>();

    static {
        SUPPORTED_ANNOTATIONS = Set.of("JsonProperty", "JsonIgnore", "JsonPropertyOrder",//
                "JsonInclude", "JsonAlias", "JsonAutoDetect", "JacksonXmlRootElement",   //
                "JacksonXmlProperty", "JacksonXmlElementWrapper", "JacksonXmlText");
    }

    public SoapXmlMapping(XmlMapper mapper) {
        this.mapper = mapper;
    }

    public static XmlMapper newMapper() {
        // Meteor 注解只公开 Java 属性，不让 Jackson 用 XML localName 合并不同 QName 的字段。
        JacksonXmlAnnotationIntrospector introspector = new JacksonXmlAnnotationIntrospector() {
            @Override
            public PropertyName findNameForSerialization(Annotated annotated) {
                if (hasMeteorMapping(annotated)) {
                    return PropertyName.USE_DEFAULT;
                }
                return super.findNameForSerialization(annotated);
            }

            @Override
            public PropertyName findNameForDeserialization(Annotated annotated) {
                if (hasMeteorMapping(annotated)) {
                    return PropertyName.USE_DEFAULT;
                }
                return super.findNameForDeserialization(annotated);
            }

            @Override
            public boolean hasIgnoreMarker(AnnotatedMember member) {
                if (member.hasAnnotation(MetXmlTransient.class)) {
                    return true;
                }
                return !hasMeteorMapping(member) && super.hasIgnoreMarker(member);
            }

            @Override
            public JsonProperty.Access findPropertyAccess(Annotated annotated) {
                if (hasMeteorMapping(annotated)) {
                    return JsonProperty.Access.AUTO;
                }
                return super.findPropertyAccess(annotated);
            }
        };
        return XmlMapper.builder() //
                .annotationIntrospector(introspector) //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN) //
                .defaultUseWrapper(false) //
                .build();
    }

    private static boolean hasMeteorMapping(Annotated annotated) {
        return annotated.hasAnnotation(MetXmlElement.class) || annotated.hasAnnotation(MetXmlAttribute.class) //
                || annotated.hasAnnotation(MetXmlValue.class) || annotated.hasAnnotation(MetXmlElementWrapper.class);
    }

    public SoapXmlType type(JavaType type) {
        return this.types.computeIfAbsent(type, this::compile);
    }

    /** 为 WSDL 多 part 的单向聚合属性解析 QName，不要求聚合对象同时可读和可写。 */
    public QName propertyName(BeanPropertyDefinition property) {
        List<AnnotatedMember> members = this.members(property, property);
        if (members.isEmpty()) {
            return new QName(property.getName());
        }
        JavaType owner = this.mapper.constructType(members.get(0).getDeclaringClass());
        return this.propertyName(property, members, owner);
    }

    public String enumValue(Enum<?> constant) {
        return this.enumName(constant);
    }

    public Object enumValue(Class<?> type, String text) {
        if (!type.isEnum()) {
            throw new IllegalArgumentException("SOAP XML enum type is required: " + type.getName());
        }
        for (Object constant : type.getEnumConstants()) {
            if (StringUtils.equals(text, this.enumName((Enum<?>) constant))) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown SOAP XML enum value for " + type.getName() + ": " + text);
    }

    public static boolean isScalar(JavaType type) {
        Class<?> raw = type.getRawClass();
        boolean text = raw == String.class || raw == char.class || raw == Character.class || raw == char[].class;
        boolean integers = raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class //
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class;
        boolean decimals = raw == float.class || raw == Float.class || raw == double.class || raw == Double.class //
                || raw == BigInteger.class || raw == BigDecimal.class;
        return text || integers || decimals || raw == boolean.class || raw == Boolean.class //
                || raw == byte[].class || raw == Date.class;
    }

    public static boolean isCollection(JavaType type) {
        return !isScalar(type) && (type.isArrayType() || Collection.class.isAssignableFrom(type.getRawClass()));
    }

    private SoapXmlType compile(JavaType type) {
        // 类型级映射与属性级映射分别编译，类型命名空间不会自动改变局部元素的限定方式。
        this.requireClassAnnotations(type);
        MetXmlType annotation = type.getRawClass().getAnnotation(MetXmlType.class);
        String name = annotation == null ? "" : annotation.name();
        String namespace = annotation == null ? "" : annotation.namespace();
        if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace)) {
            throw this.unsupported(type, "XML types cannot use the namespace declaration namespace");
        }
        if (StringUtils.isNotEmpty(name)) {
            this.requireName(name, type);
        }
        if (type.isEnumType()) {
            BeanDescription bean = this.mapper.getSerializationConfig().introspect(type);
            this.requirePlainMapping(bean, type);
            this.requireEnumeration(type);
            return new SoapXmlType(type, name, namespace, List.of(), null);
        }
        if (isCollection(type)) {
            return new SoapXmlType(type, name, namespace, List.of(), null);
        }
        this.requireOrdinaryBean(type);
        BeanDescription serialized = this.mapper.getSerializationConfig().introspect(type);
        BeanDescription deserialized = this.mapper.getDeserializationConfig().introspect(type);
        this.requirePlainMapping(serialized, type);
        this.requirePlainMapping(deserialized, type);
        if (deserialized.findDefaultConstructor() == null || deserialized.isNonStaticInnerClass()) {
            throw this.unsupported(type, "POJOs require a no-argument constructor");
        }

        // 输入输出通过 Java 属性关联，XML QName 始终保存在独立元信息中。
        Map<String, BeanPropertyDefinition> inputs = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : deserialized.findProperties()) {
            if (property.couldDeserialize()) {
                inputs.put(property.getInternalName(), property);
            }
        }
        List<SoapXmlProperty> properties = new ArrayList<>();
        for (BeanPropertyDefinition output : serialized.findProperties()) {
            if (!output.couldSerialize()) {
                continue;
            }
            BeanPropertyDefinition input = inputs.remove(output.getInternalName());
            if (input == null || !output.getPrimaryType().equals(input.getPrimaryType())) {
                String reason = "property must have matching readable and writable types: " + output.getInternalName();
                throw this.unsupported(type, reason);
            }
            properties.add(this.property(type, output, input));
        }
        if (!inputs.isEmpty() || properties.isEmpty()) {
            throw this.unsupported(type, "POJOs require matching readable and writable XML properties");
        }
        this.requirePropertyShapes(type, properties);
        List<SoapXmlProperty> ordered = this.order(type, annotation, properties);
        return new SoapXmlType(type, name, namespace, ordered, deserialized.findDefaultConstructor());
    }

    private SoapXmlProperty property(JavaType owner, BeanPropertyDefinition output, BeanPropertyDefinition input) {
        List<AnnotatedMember> members = this.members(output, input);
        for (AnnotatedMember member : members) {
            this.requireAnnotations(member.getAllAnnotations().annotations(), owner);
        }
        MetXmlElement element = this.annotation(members, MetXmlElement.class, owner);
        MetXmlAttribute attribute = this.annotation(members, MetXmlAttribute.class, owner);
        MetXmlValue text = this.annotation(members, MetXmlValue.class, owner);
        MetXmlElementWrapper wrapper = this.annotation(members, MetXmlElementWrapper.class, owner);
        boolean shapeConflict = element != null && attribute != null || element != null && text != null //
                || attribute != null && text != null;
        if (shapeConflict) {
            throw this.unsupported(owner, "conflicting XML property kinds: " + output.getInternalName());
        }

        // 显式 Meteor 注解覆盖相应 Jackson 设置；未标注的属性保留既有 Jackson 命名约定。
        boolean meteorKind = element != null || attribute != null || text != null;
        JacksonXmlProperty jacksonProperty = null;
        JacksonXmlText jacksonText = null;
        JsonProperty jsonProperty = null;
        if (!meteorKind) {
            jacksonProperty = this.annotation(members, JacksonXmlProperty.class, owner);
            jacksonText = this.annotation(members, JacksonXmlText.class, owner);
            jsonProperty = this.annotation(members, JsonProperty.class, owner);
        }
        boolean isAttribute = false;
        boolean isText = false;
        boolean required = jsonProperty != null && jsonProperty.required();
        boolean nillable = !output.getPrimaryType().isPrimitive();
        if (jacksonProperty != null) {
            isAttribute = jacksonProperty.isAttribute();
        }
        if (jacksonText != null) {
            isText = jacksonText.value();
        }
        if (element != null) {
            required = element.required();
            nillable = element.nillable() && !output.getPrimaryType().isPrimitive();
            isAttribute = false;
            isText = false;
        } else if (attribute != null) {
            required = attribute.required();
            isAttribute = true;
            isText = false;
        } else if (text != null) {
            isAttribute = false;
            isText = true;
        }
        JavaType javaType = output.getPrimaryType();
        if (isAttribute || isText) {
            nillable = false;
            if (!isScalar(javaType) && !javaType.isEnumType()) {
                throw this.unsupported(owner, "XML attributes and text require scalar or enum properties");
            }
        }
        if (isCollection(javaType)) {
            JavaType content = javaType.getContentType();
            if (content == null || content.hasRawClass(Object.class)) {
                throw this.unsupported(owner, "collection elements require a concrete Java type");
            }
        }

        // 包装是集合的结构信息，与集合中每个元素的名称和可空性分别保留。
        QName wrapperName = null;
        boolean wrapperRequired = false;
        boolean wrapperNillable = true;
        JacksonXmlElementWrapper jacksonWrapper = this.annotation(members, JacksonXmlElementWrapper.class, owner);
        if (jacksonWrapper != null && jacksonWrapper.useWrapping()) {
            String wrapperLocal = this.name(jacksonWrapper.localName(), output.getInternalName());
            wrapperName = new QName(jacksonWrapper.namespace(), wrapperLocal);
        }
        if (wrapper != null) {
            String wrapperLocal = this.name(wrapper.name(), output.getInternalName());
            wrapperName = new QName(wrapper.namespace(), wrapperLocal);
            wrapperRequired = wrapper.required();
            wrapperNillable = wrapper.nillable();
        }
        if (wrapperName != null) {
            this.requireName(wrapperName.getLocalPart(), owner);
            this.requireNamespace(wrapperName, false, owner);
            if (!isCollection(javaType) || isAttribute || isText) {
                throw this.unsupported(owner, "XML element wrappers require collection properties");
            }
        }
        AnnotatedMember reader = output.getAccessor();
        AnnotatedMember writer = input.getNonConstructorMutator();
        if (reader == null || writer == null) {
            throw this.unsupported(owner, "XML properties require ordinary readable and writable members");
        }
        QName xmlName = this.propertyName(output, members, owner);
        this.requireNamespace(xmlName, isAttribute, owner);
        return new SoapXmlProperty(output.getInternalName(), javaType, // Java 属性
                xmlName, isAttribute, isText, required, nillable, // 元素、属性或文本
                wrapperName, wrapperRequired, wrapperNillable, // 集合包装
                reader, writer);
    }

    private QName propertyName(BeanPropertyDefinition property, List<AnnotatedMember> members, JavaType owner) {
        MetXmlElement element = this.annotation(members, MetXmlElement.class, owner);
        MetXmlAttribute attribute = this.annotation(members, MetXmlAttribute.class, owner);
        if (element != null && attribute != null) {
            throw this.unsupported(owner, "conflicting XML property kinds: " + property.getInternalName());
        }
        String localName = property.getName();
        String namespace = "";
        if (element != null) {
            localName = this.name(element.name(), property.getInternalName());
            namespace = element.namespace();
        } else if (attribute != null) {
            localName = this.name(attribute.name(), property.getInternalName());
            namespace = attribute.namespace();
        } else {
            JacksonXmlProperty xml = this.annotation(members, JacksonXmlProperty.class, owner);
            JsonProperty json = this.annotation(members, JsonProperty.class, owner);
            if (xml != null) {
                localName = this.name(xml.localName(), localName);
                namespace = xml.namespace();
            } else if (json != null) {
                localName = this.name(json.value(), localName);
                namespace = json.namespace();
            }
        }
        this.requireName(localName, owner);
        QName name = new QName(namespace, localName);
        this.requireNamespace(name, attribute != null, owner);
        return name;
    }

    private void requireNamespace(QName name, boolean attribute, JavaType owner) {
        boolean declarationNamespace = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, name.getNamespaceURI());
        boolean defaultDeclaration = attribute && StringUtils.isEmpty(name.getNamespaceURI()) //
                && StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE, name.getLocalPart());
        if (declarationNamespace || defaultDeclaration) {
            throw this.unsupported(owner, "namespace declarations cannot be mapped as XML properties: " + name);
        }
    }

    private List<AnnotatedMember> members(BeanPropertyDefinition output, BeanPropertyDefinition input) {
        Set<AnnotatedMember> result = new LinkedHashSet<>();
        Collections.addAll(result, output.getField(), output.getGetter(), output.getSetter(), //
                input.getField(), input.getGetter(), input.getSetter());
        result.remove(null);
        return new ArrayList<>(result);
    }

    private <A extends Annotation> A annotation(List<AnnotatedMember> members, // 成员上的声明
            Class<A> annotationType, JavaType owner) {
        A result = null;
        for (AnnotatedMember member : members) {
            AnnotatedElement actual = (AnnotatedElement) member.getMember();
            A annotation = actual.getAnnotation(annotationType);
            if (annotation != null) {
                if (result != null && !result.equals(annotation)) {
                    String reason = "conflicting " + annotationType.getSimpleName() + " mappings: " + member.getName();
                    throw this.unsupported(owner, reason);
                }
                result = annotation;
            }
        }
        // Jackson 还会合并接口上的声明；仅在实际成员没有声明时使用这个补充信息。
        if (result == null) {
            for (AnnotatedMember member : members) {
                A annotation = member.getAnnotation(annotationType);
                if (annotation != null) {
                    if (result != null && !result.equals(annotation)) {
                        String reason = "conflicting inherited " + annotationType.getSimpleName() //
                                + " mappings: " + member.getName();
                        throw this.unsupported(owner, reason);
                    }
                    result = annotation;
                }
            }
        }
        return result;
    }

    private void requirePropertyShapes(JavaType owner, List<SoapXmlProperty> properties) {
        Set<QName> elements = new HashSet<>();
        Set<QName> attributes = new HashSet<>();
        boolean text = false;
        for (SoapXmlProperty property : properties) {
            if (property.isText()) {
                if (text) {
                    throw this.unsupported(owner, "only one XML text property is allowed");
                }
                text = true;
                continue;
            }
            Set<QName> names = property.isAttribute() ? attributes : elements;
            QName name = property.getWrapper() == null ? property.getName() : property.getWrapper();
            if (!names.add(name)) {
                throw this.unsupported(owner, "duplicate XML property name: " + name);
            }
        }
        if (text && !elements.isEmpty()) {
            throw this.unsupported(owner, "XML text properties may coexist with attributes, not child elements");
        }
    }

    private List<SoapXmlProperty> order(JavaType owner, MetXmlType annotation, List<SoapXmlProperty> properties) {
        if (annotation == null || annotation.propOrder().length == 0) {
            return properties;
        }
        Map<String, SoapXmlProperty> remaining = new LinkedHashMap<>();
        for (SoapXmlProperty property : properties) {
            remaining.put(property.getJavaName(), property);
        }
        List<SoapXmlProperty> result = new ArrayList<>();
        for (String name : annotation.propOrder()) {
            SoapXmlProperty property = remaining.remove(name);
            if (property == null) {
                throw this.unsupported(owner, "unknown or duplicate XML property in propOrder: " + name);
            }
            result.add(property);
        }
        for (SoapXmlProperty property : remaining.values()) {
            if (!property.isAttribute()) {
                throw this.unsupported(owner, "propOrder must include every XML element or text property");
            }
            result.add(property);
        }
        return result;
    }

    private void requireEnumeration(JavaType owner) {
        Set<String> values = new HashSet<>();
        for (Object constant : owner.getRawClass().getEnumConstants()) {
            String value = this.enumName((Enum<?>) constant);
            if (!values.add(value)) {
                throw this.unsupported(owner, "duplicate XML enum value: " + value);
            }
        }
    }

    private String enumName(Enum<?> constant) {
        try {
            Field field = constant.getDeclaringClass().getField(constant.name());
            MetXmlEnumValue meteor = field.getAnnotation(MetXmlEnumValue.class);
            if (meteor != null) {
                return meteor.value();
            }
            JsonProperty jackson = field.getAnnotation(JsonProperty.class);
            if (jackson != null && StringUtils.isNotEmpty(jackson.value())) {
                return jackson.value();
            }
            return constant.name();
        } catch (NoSuchFieldException failure) {
            throw new IllegalArgumentException("Unable to inspect SOAP XML enum constant", failure);
        }
    }

    private void requireOrdinaryBean(JavaType type) {
        Class<?> raw = type.getRawClass();
        boolean specialValue = Number.class.isAssignableFrom(raw) || Date.class.isAssignableFrom(raw) //
                || CharSequence.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw);
        boolean streaming = Flow.Publisher.class.isAssignableFrom(raw) || BaseStream.class.isAssignableFrom(raw) //
                || InputStream.class.isAssignableFrom(raw) || OutputStream.class.isAssignableFrom(raw) //
                || Reader.class.isAssignableFrom(raw) || Writer.class.isAssignableFrom(raw);
        boolean abstractType = raw.isInterface() || Modifier.isAbstract(raw.getModifiers());
        if (abstractType || specialValue || streaming || raw.getName().startsWith("java.")) {
            throw this.unsupported(type, "only concrete ordinary POJOs are supported");
        }
    }

    private void requirePlainMapping(BeanDescription bean, JavaType owner) {
        boolean customized = bean.findJsonValueAccessor() != null || bean.findAnyGetter() != null //
                || bean.findAnySetterAccessor() != null || bean.getObjectIdInfo() != null;
        if (customized) {
            String reason = "custom value, dynamic property and object identity mappings are not supported";
            throw this.unsupported(owner, reason);
        }
    }

    private void requireClassAnnotations(JavaType owner) {
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(owner.getRawClass());
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!visited.add(type)) {
                continue;
            }
            this.requireAnnotations(Arrays.asList(type.getDeclaredAnnotations()), owner);
            Collections.addAll(pending, type.getInterfaces());
            if (type.getSuperclass() != null) {
                pending.add(type.getSuperclass());
            }
        }
    }

    private void requireAnnotations(Iterable<Annotation> annotations, JavaType owner) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            boolean jackson = type.getName().startsWith("com.fasterxml.jackson.");
            boolean unsupported = jackson && !SUPPORTED_ANNOTATIONS.contains(type.getSimpleName());
            if (unsupported || type.isAnnotationPresent(JacksonAnnotationsInside.class)) {
                throw this.unsupported(owner, "custom Jackson mapping is not supported: " + type.getSimpleName());
            }
        }
    }

    private String name(String configured, String fallback) {
        return StringUtils.isEmpty(configured) ? fallback : configured;
    }

    private void requireName(String name, JavaType owner) {
        if (StringUtils.isBlank(name) || name.indexOf(':') >= 0) {
            throw this.unsupported(owner, "XML property names must be local names: " + name);
        }
        try {
            Document document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().newDocument();
            document.createElementNS(null, name);
        } catch (DOMException failure) {
            throw this.unsupported(owner, "invalid XML local name: " + name);
        } catch (ParserConfigurationException failure) {
            throw new IllegalStateException("Unable to validate SOAP XML local names", failure);
        }
    }

    private IllegalArgumentException unsupported(JavaType type, String reason) {
        return new IllegalArgumentException("Cannot map SOAP XML type " + type.toCanonical() + ": " + reason);
    }
}
