/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.stream.BaseStream;
import javax.xml.XMLConstants;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 以 SOAP 消息实际使用的 Jackson 属性模型生成 XML Schema。 */
final class SoapSchemaGenerator {
    private static final String                XSD_NAMESPACE         = SoapWsdlParser.XSD_NAMESPACE;
    private static final Set<String>           SUPPORTED_ANNOTATIONS = Set.of(//
            "JsonProperty", "JsonIgnore", "JsonPropertyOrder", "JsonInclude", "JsonAlias", "JsonAutoDetect", //
            "JacksonXmlRootElement", "JacksonXmlProperty");
    private final        XmlMapper             mapper                = newMapper();
    private final        Document              document;
    private final        Element               schema;
    private final        SoapVersion           encodingVersion;
    private final        Map<JavaType, String> types                 = new LinkedHashMap<>();

    SoapSchemaGenerator(String namespace, SoapVersion encodingVersion) {
        this.encodingVersion = encodingVersion;
        this.document = SoapXmlUtils.newDocument();
        this.schema = this.document.createElementNS(XSD_NAMESPACE, "xsd:schema");
        this.document.appendChild(this.schema);
        this.schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD_NAMESPACE);
        this.schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:tns", namespace);
        this.schema.setAttribute("targetNamespace", namespace);
        this.schema.setAttribute("elementFormDefault", "unqualified");
        if (encodingVersion != null) {
            String encoding = encodingVersion == SoapVersion.SOAP_11 //
                    ? "http://schemas.xmlsoap.org/soap/encoding/" : "http://www.w3.org/2003/05/soap-encoding";
            this.schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:soapenc", encoding);
            this.schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsdl", //
                    SoapWsdlParser.WSDL_NAMESPACE);
            Element imported = this.append(this.schema, "import");
            imported.setAttribute("namespace", encoding);
        }
    }

    private static XmlMapper newMapper() {
        return XmlMapper.builder() //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN) //
                .defaultUseWrapper(false) //
                .build();
    }

    Document document() {
        return this.document;
    }

    void operation(Method method, boolean oneWay) {
        // operation 包装始终存在；参数和返回值使用稳定的局部元素名称。
        Element input = this.wrapper(method.getName());
        Type[] parameters = method.getGenericParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            JavaType type = this.mapper.constructType(parameters[index]);
            this.value(input, "arg" + index, type, false);
        }
        if (!oneWay) {
            Element output = this.wrapper(method.getName() + "Response");
            if (method.getReturnType() != void.class) {
                JavaType type = this.mapper.constructType(method.getGenericReturnType());
                this.value(output, "return", type, false);
            }
        }
    }

    private Element wrapper(String name) {
        Element wrapper = this.append(this.schema, "element");
        wrapper.setAttribute("name", name);
        Element complexType = this.append(wrapper, "complexType");
        return this.append(complexType, "sequence");
    }

    private void value(Element parent, String name, JavaType type, boolean optional) {
        SoapXmlUtils.requireName(this.document, name, "SOAP property");
        Element element = this.append(parent, "element");
        element.setAttribute("name", name);
        element.setAttribute("type", this.typeName(type));
        if (optional) {
            element.setAttribute("minOccurs", "0");
        }
        if (!type.isPrimitive()) {
            element.setAttribute("nillable", "true");
        }
    }

    private String typeName(JavaType type) {
        String scalar = this.scalar(type.getRawClass());
        if (scalar != null) {
            return "xsd:" + scalar;
        }
        String existing = this.types.get(type);
        if (existing != null) {
            return "tns:" + existing;
        }
        if (this.types.size() >= 256) {
            throw this.unsupported(type, "more than 256 distinct XML types");
        }
        if (type.hasRawClass(Object.class) || type.isMapLikeType() || type.isReferenceType()) {
            throw this.unsupported(type, "a concrete XML value type is required");
        }

        // 先登记类型名称，允许普通 POJO 自引用，同时区分相同类的不同泛型实例。
        String name = "Type" + (this.types.size() + 1);
        this.types.put(type, name);
        if (type.isEnumType()) {
            this.enumeration(type, name);
        } else if (this.collection(type)) {
            this.array(type, name);
        } else {
            this.bean(type, name);
        }
        return "tns:" + name;
    }

    private String scalar(Class<?> type) {
        if (type == String.class || type == char.class || type == Character.class || type == char[].class) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == byte.class || type == Byte.class) {
            return "byte";
        }
        if (type == short.class || type == Short.class) {
            return "short";
        }
        if (type == int.class || type == Integer.class) {
            return "int";
        }
        if (type == long.class || type == Long.class || type == Date.class) {
            return "long";
        }
        if (type == float.class || type == Float.class) {
            return "float";
        }
        if (type == double.class || type == Double.class) {
            return "double";
        }
        if (type == BigInteger.class) {
            return "integer";
        }
        if (type == BigDecimal.class) {
            return "decimal";
        }
        return type == byte[].class ? "base64Binary" : null;
    }

    private boolean collection(JavaType type) {
        return type.isArrayType() || Collection.class.isAssignableFrom(type.getRawClass());
    }

    private JavaType contentType(JavaType type) {
        JavaType content = type.getContentType();
        if (content == null || content.hasRawClass(Object.class)) {
            throw this.unsupported(type, "collection elements require a concrete Java type");
        }
        if (this.encodingVersion == null && this.collection(content) && this.scalar(content.getRawClass()) == null) {
            throw this.unsupported(type, "nested collections do not retain their structure in XML");
        }
        return content;
    }

    private void array(JavaType type, String name) {
        Element complex = this.namedType(name, "complexType");
        JavaType content = this.contentType(type);
        if (this.encodingVersion == null) {
            this.repeated(this.append(complex, "sequence"), "item", content);
            return;
        }

        // 多维 Java 数组使用 SOAP array 的 rank；集合嵌套保留各层数组类型。
        int dimensions = 1;
        while (type.isArrayType() && content.isArrayType() && this.scalar(content.getRawClass()) == null) {
            dimensions++;
            content = this.contentType(content);
        }
        Element container = complex;
        if (this.encodingVersion == SoapVersion.SOAP_11) {
            container = this.append(this.append(complex, "complexContent"), "restriction");
            container.setAttribute("base", "soapenc:Array");
        }
        this.repeated(this.append(container, "sequence"), "item", content);
        if (this.encodingVersion == SoapVersion.SOAP_11) {
            Element attribute = this.append(container, "attribute");
            attribute.setAttribute("ref", "soapenc:arrayType");
            String rank = "[" + ",".repeat(dimensions - 1) + "]";
            attribute.setAttributeNS(SoapWsdlParser.WSDL_NAMESPACE, "wsdl:arrayType", //
                    this.typeName(content) + rank);
        } else {
            // SOAP 1.2 不定义 enc:Array 类型，数组由标准属性组描述。
            Element arrays = this.append(container, "attributeGroup");
            arrays.setAttribute("ref", "soapenc:arrayAttributes");
            Element references = this.append(container, "attributeGroup");
            references.setAttribute("ref", "soapenc:commonAttributes");
        }
    }

    private void repeated(Element sequence, String name, JavaType content) {
        this.value(sequence, name, content, true);
        Element repeated = (Element) sequence.getLastChild();
        repeated.setAttribute("maxOccurs", "unbounded");
    }

    private void enumeration(JavaType type, String name) {
        BeanDescription bean = this.mapper.getSerializationConfig().introspect(type);
        this.requirePlainMapping(bean, type);
        Element simple = this.namedType(name, "simpleType");
        Element restriction = this.append(simple, "restriction");
        restriction.setAttribute("base", "xsd:string");
        for (Object constant : type.getRawClass().getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            String serialized = value.name();
            try {
                JsonProperty property = type.getRawClass().getField(value.name()).getAnnotation(JsonProperty.class);
                if (property != null && StringUtils.isNotEmpty(property.value())) {
                    serialized = property.value();
                }
            } catch (NoSuchFieldException failure) {
                throw new IllegalStateException("Unable to inspect SOAP enum constant", failure);
            }
            Element enumeration = this.append(restriction, "enumeration");
            enumeration.setAttribute("value", serialized);
        }
    }

    private void bean(JavaType type, String name) {
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
        BeanDescription serialized = this.mapper.getSerializationConfig().introspect(type);
        BeanDescription deserialized = this.mapper.getDeserializationConfig().introspect(type);
        this.requirePlainMapping(serialized, type);
        this.requirePlainMapping(deserialized, type);
        if (deserialized.findDefaultConstructor() == null || deserialized.isNonStaticInnerClass()) {
            throw this.unsupported(type, "POJOs require a no-argument constructor");
        }
        Map<String, BeanPropertyDefinition> inputs = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : deserialized.findProperties()) {
            if (property.couldDeserialize()) {
                inputs.put(property.getName(), property);
            }
        }
        Element complex = this.namedType(name, "complexType");
        Element sequence = this.append(complex, "sequence");
        int properties = 0;
        for (BeanPropertyDefinition property : serialized.findProperties()) {
            if (!property.couldSerialize()) {
                continue;
            }
            BeanPropertyDefinition input = inputs.remove(property.getName());
            if (input == null || !property.getPrimaryType().equals(input.getPrimaryType())) {
                String reason = "property must have matching readable and writable types: " + property.getName();
                throw this.unsupported(type, reason);
            }
            this.requireProperty(property, type);
            this.requireProperty(input, type);
            JavaType propertyType = property.getPrimaryType();
            if (this.encodingVersion == null && this.collection(propertyType) //
                    && this.scalar(propertyType.getRawClass()) == null) {
                this.repeated(sequence, property.getName(), this.contentType(propertyType));
            } else {
                this.value(sequence, property.getName(), propertyType, true);
            }
            properties++;
        }
        if (!inputs.isEmpty() || properties == 0) {
            throw this.unsupported(type, "POJOs require matching readable and writable XML properties");
        }
    }

    private void requirePlainMapping(BeanDescription bean, JavaType type) {
        this.requireClassAnnotations(type);
        boolean customized = bean.findJsonValueAccessor() != null || bean.findAnyGetter() != null //
                || bean.findAnySetterAccessor() != null || bean.getObjectIdInfo() != null;
        if (customized) {
            String reason = "custom value, dynamic property and object identity mappings are not supported";
            throw this.unsupported(type, reason);
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

    private void requireProperty(BeanPropertyDefinition property, JavaType owner) {
        this.requireMember(property.getPrimaryMember(), owner);
        this.requireMember(property.getField(), owner);
        this.requireMember(property.getGetter(), owner);
        this.requireMember(property.getSetter(), owner);
    }

    private void requireMember(AnnotatedMember member, JavaType owner) {
        if (member == null) {
            return;
        }
        this.requireAnnotations(member.getAllAnnotations().annotations(), owner);
        JacksonXmlProperty property = member.getAnnotation(JacksonXmlProperty.class);
        if (property != null && (property.isAttribute() || StringUtils.isNotEmpty(property.namespace()))) {
            throw this.unsupported(owner, "XML attributes and custom property namespaces are not supported");
        }
        JsonProperty jsonProperty = member.getAnnotation(JsonProperty.class);
        if (jsonProperty != null && StringUtils.isNotEmpty(jsonProperty.namespace())) {
            throw this.unsupported(owner, "custom property namespaces are not supported");
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

    private Element namedType(String name, String kind) {
        Element type = this.append(this.schema, kind);
        type.setAttribute("name", name);
        return type;
    }

    private Element append(Element parent, String localName) {
        return SoapXmlUtils.append(parent, XSD_NAMESPACE, "xsd:" + localName);
    }

    private IllegalArgumentException unsupported(JavaType type, String reason) {
        return new IllegalArgumentException("Cannot generate SOAP schema for " + type.toCanonical() + ": " + reason);
    }
}
