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
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.meta.EnumValueMeta;
import net.hasor.meteor.domain.meta.PropertyMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.meta.TypeMeta;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;

/** 定义生成与 XML 编解码共享的映射编译器，QName 与 Java 属性名称相互独立。 */
public final class SoapXmlMapping {
    private static final int                        JACKSON_VALUE   = 1;
    private static final int                        JACKSON_CONTENT = 2;
    private final        XmlMapper                  mapper;
    private final        XmlMapper                  bindings;
    private final        Map<JavaType, SoapXmlType> types           = new ConcurrentHashMap<>();
    private final        Map<JavaType, Boolean>     jacksonValues   = new ConcurrentHashMap<>();

    public SoapXmlMapping(XmlMapper mapper) {
        this.mapper = mapper;
        this.bindings = mapper.copy();
        // XML 包装由本映射展开；Java token 绑定不再让 XmlMapper 第二次改写构造参数名。
        AnnotationIntrospector noWrapper = new NopAnnotationIntrospector() {
            @Override
            public PropertyName findWrapperName(Annotated value) {
                return PropertyName.NO_NAME;
            }
        };
        this.bindings.setAnnotationIntrospectors( //
                AnnotationIntrospector.pair(noWrapper, mapper.getSerializationConfig().getAnnotationIntrospector()), //
                AnnotationIntrospector.pair(noWrapper, mapper.getDeserializationConfig().getAnnotationIntrospector()));
    }

    public static XmlMapper newMapper() {
        XmlMapper mapper = XmlMapper.builder() //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN) //
                .defaultUseWrapper(false) //
                .build();
        return configuredMapper(mapper);
    }

    /** 固化用户 Mapper 的配置，合并公共名称优先级，不修改借入的实例。 */
    public static XmlMapper configuredMapper(XmlMapper template) {
        if (template == null) {
            return newMapper();
        }
        XmlMapper mapper = template.copy();
        mapper.setAnnotationIntrospectors( //
                soapIntrospector(mapper.getSerializationConfig().getAnnotationIntrospector()), //
                soapIntrospector(mapper.getDeserializationConfig().getAnnotationIntrospector()));
        return mapper;
    }

    private static AnnotationIntrospector soapIntrospector(AnnotationIntrospector delegate) {
        AnnotationIntrospector soap = new NopAnnotationIntrospector() {
            @Override
            public PropertyName findNameForSerialization(Annotated annotated) {
                return hasMeteorMapping(annotated) ? PropertyName.USE_DEFAULT : null;
            }

            @Override
            public PropertyName findNameForDeserialization(Annotated annotated) {
                return hasMeteorMapping(annotated) ? PropertyName.USE_DEFAULT : null;
            }

        };
        return new AnnotationIntrospectorPair(soap, delegate) {
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
            public String[] findEnumValues(MapperConfig<?> config, AnnotatedClass type, // 枚举类型
                    Enum<?>[] values, String[] names) {
                String[] resolved = super.findEnumValues(config, type, values, names);
                for (int index = 0; index < values.length; index++) {
                    EnumValueMeta annotation = ServiceMetaParser.enumValue(values[index]);
                    if (!annotation.getDeclaredName().isEmpty()) {
                        resolved[index] = annotation.getName();
                    }
                }
                return resolved;
            }

        };
    }

    private static boolean hasMeteorMapping(Annotated annotated) {
        return ServiceMetaParser.isProperty(annotated);
    }

    public SoapXmlType type(JavaType type) {
        return this.types.computeIfAbsent(type, this::compile);
    }

    public XmlMapper mapper() {
        return this.mapper;
    }

    /** 仅导出发布阶段已准备的 XML 属性，不创建 DTO 或重新配置 mapper。 */
    public ObjectNode describeTypes() {
        ObjectNode types = JsonNodeFactory.instance.objectNode();
        for (SoapXmlType type : this.types.values()) {
            ObjectNode entry = types.putObject(type.getJavaType().toCanonical());
            entry.put("name", type.getName());
            ArrayNode fields = entry.putArray("properties");
            for (SoapXmlProperty property : type.getProperties()) {
                ObjectNode field = fields.addObject();
                field.put("name", property.getName().getLocalPart());
                field.put("javaName", property.getJavaName());
                field.put("type", property.getJavaType().toCanonical());
                field.put("namespace", property.getName().getNamespaceURI());
                field.put("attribute", property.isAttribute());
                field.put("text", property.isText());
                field.put("required", property.isRequired());
                field.put("nillable", property.isNillable());
                if (property.getWrapper() != null) {
                    field.put("wrapper", property.getWrapper().toString());
                }
            }
            entry.put("namespace", type.getNamespace());
        }
        return types;
    }

    /** 保留相同 Jackson 定制、只接收已展开 Java token 的绑定器。 */
    public XmlMapper bindings() {
        return this.bindings;
    }

    /** 在发布阶段编译声明类型图，递归字段只登记一次。 */
    public void prepare(JavaType type) {
        this.prepare(type, new HashSet<>());
    }

    private void prepare(JavaType type, Set<JavaType> visited) {
        if (type == null || isScalar(type) || type.hasRawClass(Object.class) || !visited.add(type)) {
            return;
        }
        if (isCollection(type) || isMap(type)) {
            this.prepare(type.getContentType(), visited);
            return;
        }
        SoapXmlType compiled = this.type(type);
        for (SoapXmlProperty property : compiled.getProperties()) {
            if (!property.isJacksonValue()) {
                this.prepare(property.getJavaType(), visited);
            }
        }
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

    public static boolean isMap(JavaType type) {
        return Map.class.isAssignableFrom(type.getRawClass());
    }

    private SoapXmlType compile(JavaType type) {
        // 类型级映射与属性级映射分别编译，类型命名空间不会自动改变局部元素的限定方式。
        TypeMeta annotation = ServiceMetaParser.type(type.getRawClass());
        JacksonXmlRootElement root = type.getRawClass().getAnnotation(JacksonXmlRootElement.class);
        String name = "";
        if (annotation.isDeclared()) {
            name = annotation.getName();
            if (StringUtils.isBlank(name)) {
                throw this.unsupported(type, "MetType.name must not be blank");
            }
        } else if (root != null) {
            name = root.localName();
        }
        String namespace = root == null ? "" : root.namespace();
        if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace)) {
            throw this.unsupported(type, "XML types cannot use the namespace declaration namespace");
        }
        if (StringUtils.isNotEmpty(name)) {
            this.requireName(name, type);
        }
        boolean jacksonValue = this.isJacksonValue(type);
        if (jacksonValue && !this.hasPropertyShape(type)) {
            // 自定义值序列化器不假定普通 Bean 的字段、构造器或 setter 契约。
            return new SoapXmlType(type, name, namespace, List.of(), null, true);
        }
        if (type.isEnumType()) {
            this.requireEnumeration(type);
            return new SoapXmlType(type, name, namespace, List.of(), null, false);
        }
        if (type.hasRawClass(Object.class) || isCollection(type)) {
            // Object 是开放的 XML 内容终点，不要求具有可发现的 Java Bean 属性。
            return new SoapXmlType(type, name, namespace, List.of(), null, false);
        }
        if (isMap(type)) {
            this.requireMapType(type, type);
            return new SoapXmlType(type, name, namespace, List.of(), null, false);
        }
        if (!jacksonValue) {
            this.requireOrdinaryBean(type);
        }
        BeanDescription serialized = this.mapper.getSerializationConfig().introspect(type);
        BeanDescription deserialized = this.mapper.getDeserializationConfig().introspect(type);
        if (!jacksonValue && deserialized.isNonStaticInnerClass()) {
            throw this.unsupported(type, "non-static inner POJOs are not supported");
        }
        if (!jacksonValue) {
            this.requireJacksonConstructor(type);
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
            properties.add(this.property(type, output, input));
        }
        for (BeanPropertyDefinition input : inputs.values()) {
            properties.add(this.property(type, null, input));
        }
        if (properties.isEmpty() && this.mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS)) {
            throw this.unsupported(type, "POJOs require Jackson XML properties or a custom serializer");
        }
        this.requirePropertyShapes(type, properties);
        // 数字越小越先输出；稳定排序让相同 order 保持原映射发现顺序。
        properties.sort(Comparator.comparingInt(SoapXmlProperty::getOrder));
        return new SoapXmlType(type, name, namespace, properties, deserialized.findDefaultConstructor(), jacksonValue);
    }

    private SoapXmlProperty property(JavaType owner, BeanPropertyDefinition output, BeanPropertyDefinition input) {
        List<AnnotatedMember> members = this.members(output, input);
        BeanPropertyDefinition property = output == null ? input : output;
        JacksonXmlProperty xml = this.annotation(members, JacksonXmlProperty.class, owner);
        JacksonXmlText text = this.annotation(members, JacksonXmlText.class, owner);
        JsonProperty json = this.annotation(members, JsonProperty.class, owner);
        JsonSetter setter = this.annotation(members, JsonSetter.class, owner);
        boolean isAttribute = xml != null && xml.isAttribute();
        boolean isText = text != null && text.value();
        if (isAttribute && isText) {
            throw this.unsupported(owner, "conflicting XML property kinds: " + property.getInternalName());
        }

        // 公共注解只提供名称；类别、可空性、顺序继续由 Jackson 映射确定。
        Integer declaredOrder = property.getMetadata().getIndex();
        int order = declaredOrder == null ? 0 : declaredOrder;
        boolean required = output != null && input != null && json != null && json.required();
        boolean rejectNull = setter != null && setter.nulls() == Nulls.FAIL;
        boolean nillable = !property.getPrimaryType().isPrimitive() && !rejectNull;
        JavaType javaType = property.getPrimaryType();
        boolean jacksonType = this.isJacksonValue(javaType);
        int jacksonBindings = this.jacksonPropertyBindings(members, javaType);
        boolean jacksonValue = jacksonType || jacksonBindings != 0;
        boolean jacksonContent = !jacksonType && jacksonBindings == JACKSON_CONTENT;
        if (output != null && input != null && !output.getPrimaryType().equals(input.getPrimaryType())) {
            jacksonValue = true;
            jacksonContent = false;
        }
        if (isMap(javaType)) {
            this.requireMapType(javaType, owner);
        }
        if (isAttribute || isText) {
            nillable = false;
            JavaType valueType = isAttribute && isMap(javaType) ? javaType.getContentType() : javaType;
            boolean dynamicScalar = isAttribute && isMap(javaType) && valueType.hasRawClass(Object.class);
            if (!jacksonValue && !isScalar(valueType) && !valueType.isEnumType() && !dynamicScalar) {
                throw this.unsupported(owner, "XML attributes and text require scalar or enum properties");
            }
        }
        // Jackson 的包装声明用于集合；复杂的标量或属性容器使用普通 DTO 或外部契约表达。
        QName wrapperName = null;
        boolean wrapperRequired = false;
        boolean wrapperNillable = true;
        JacksonXmlElementWrapper wrapper = this.annotation(members, JacksonXmlElementWrapper.class, owner);
        if (wrapper != null && wrapper.useWrapping()) {
            String localName = this.name(wrapper.localName(), property.getInternalName());
            wrapperName = new QName(wrapper.namespace(), localName);
            wrapperRequired = required;
            wrapperNillable = nillable;
            this.requireName(localName, owner);
            this.requireNamespace(wrapperName, false, owner);
            if (isText || isAttribute || !isCollection(javaType)) {
                throw this.unsupported(owner, "XML element wrappers require collection element properties");
            }
        }
        AnnotatedMember reader = output == null ? null : output.getAccessor();
        AnnotatedMember writer = input == null ? null : input.getNonConstructorMutator();
        QName xmlName = this.propertyName(property, members, owner);
        if (isAttribute && isMap(javaType)) {
            this.requireDynamicAttributeNamespace(xmlName.getNamespaceURI(), owner);
        } else {
            this.requireNamespace(xmlName, isAttribute, owner);
        }
        List<String> aliases = new ArrayList<>();
        if (input != null) {
            for (PropertyName alias : input.findAliases()) {
                this.requireName(alias.getSimpleName(), owner);
                aliases.add(alias.getSimpleName());
            }
        }
        return new SoapXmlProperty(property.getInternalName(), property.getName(), aliases, // Jackson 绑定名称
                javaType, jacksonValue, jacksonContent, order, // Java 属性及顺序
                xmlName, isAttribute, isText, required, nillable, // 元素、属性或文本
                wrapperName, wrapperRequired, wrapperNillable, // 元素包装
                reader, writer, input == null ? output.getPrimaryMember() : input.getPrimaryMember());
    }

    private QName propertyName(BeanPropertyDefinition property, List<AnnotatedMember> members, JavaType owner) {
        String localName = property.getName();
        String namespace = "";
        JacksonXmlProperty xml = this.annotation(members, JacksonXmlProperty.class, owner);
        JsonProperty json = this.annotation(members, JsonProperty.class, owner);
        if (xml != null) {
            localName = this.name(xml.localName(), localName);
            namespace = xml.namespace();
        } else if (json != null) {
            localName = this.name(json.value(), localName);
            namespace = json.namespace();
        }
        PropertyMeta declaration = ServiceMetaParser.property(property.getInternalName(), //
                property.getPrimaryType(), members);
        String commonName = declaration.getDeclaredName().isEmpty() ? null : declaration.getDeclaredName();
        boolean dynamicAttributes = xml != null && xml.isAttribute() && isMap(property.getPrimaryType());
        if (commonName != null && !dynamicAttributes) {
            localName = commonName;
        }
        this.requireName(localName, owner);
        QName name = new QName(namespace, localName);
        boolean attribute = xml != null && xml.isAttribute();
        if (!attribute || !isMap(property.getPrimaryType())) {
            this.requireNamespace(name, attribute, owner);
        }
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
        if (output != null) {
            Collections.addAll(result, output.getField(), output.getGetter(), output.getSetter(), //
                    output.getConstructorParameter());
        }
        if (input != null) {
            Collections.addAll(result, input.getField(), input.getGetter(), input.getSetter(), //
                    input.getConstructorParameter());
        }
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
        Set<String> dynamicAttributeNamespaces = new HashSet<>();
        boolean text = false;
        for (SoapXmlProperty property : properties) {
            if (property.isText()) {
                if (text) {
                    throw this.unsupported(owner, "only one XML text property is allowed");
                }
                text = true;
                continue;
            }
            if (property.isAttribute()) {
                if (isMap(property.getJavaType())) {
                    if (!dynamicAttributeNamespaces.add(property.getName().getNamespaceURI())) {
                        throw this.unsupported(owner, "only one dynamic XML attribute Map is allowed per namespace");
                    }
                } else {
                    for (QName name : this.propertyNames(property)) {
                        if (!attributes.add(name)) {
                            throw this.unsupported(owner, "duplicate XML attribute name: " + name);
                        }
                    }
                }
                continue;
            }
            QName wrapper = property.getWrapper();
            Set<QName> names = wrapper == null ? this.propertyNames(property) : Set.of(wrapper);
            for (QName name : names) {
                if (!elements.add(name)) {
                    throw this.unsupported(owner, "duplicate XML property name: " + name);
                }
            }
        }
        if (text && !elements.isEmpty()) {
            throw this.unsupported(owner, "XML text properties may coexist with attributes, not child elements");
        }
    }

    private Set<QName> propertyNames(SoapXmlProperty property) {
        Set<QName> names = new LinkedHashSet<>();
        names.add(property.getName());
        for (String alias : property.getAliases()) {
            names.add(new QName(property.getName().getNamespaceURI(), alias));
        }
        return names;
    }

    private void requireMapType(JavaType type, JavaType owner) {
        JavaType key = type.getKeyType();
        if (key == null || !key.hasRawClass(String.class)) {
            throw this.unsupported(owner, "XML Maps require String keys: " + type.toCanonical());
        }
        // value 的声明泛型由类型图遍历继续解析；元素可承载 DTO、容器或 Object 开放内容。
        // 属性 Map 的标量限制在属性形态校验中独立执行。
    }

    private void requireDynamicAttributeNamespace(String namespace, JavaType owner) {
        boolean xml = StringUtils.equals(XMLConstants.XML_NS_URI, namespace);
        boolean xmlns = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace);
        boolean xsi = StringUtils.equals(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, namespace);
        boolean soapEnvelope = StringUtils.equals("http://schemas.xmlsoap.org/soap/envelope/", namespace) //
                || StringUtils.equals("http://www.w3.org/2003/05/soap-envelope", namespace);
        boolean soapEncoding = StringUtils.equals("http://schemas.xmlsoap.org/soap/encoding/", namespace) //
                || StringUtils.equals("http://www.w3.org/2003/05/soap-encoding", namespace);
        if (xml || xmlns || xsi || soapEnvelope || soapEncoding) {
            throw this.unsupported(owner, "dynamic XML attributes cannot use a reserved XML namespace: " + namespace);
        }
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
            JacksonXmlProperty xml = field.getAnnotation(JacksonXmlProperty.class);
            if (xml != null && xml.isAttribute()) {
                String location = field.getDeclaringClass().getName() + "#" + field.getName();
                throw new IllegalArgumentException("XML attribute has no parent element on enum constant: " + location);
            }
            EnumValueMeta meteor = ServiceMetaParser.enumValue(constant);
            if (!meteor.getDeclaredName().isEmpty()) {
                return meteor.getName();
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

    /** 判断当前配置是否让该声明值脱离普通属性映射，供 Schema 与 literal 绑定共用。 */
    public boolean isJacksonValue(JavaType type) {
        return this.jacksonValues.computeIfAbsent(type, this::jacksonValue);
    }

    private boolean hasPropertyShape(JavaType type) {
        boolean scalar = isScalar(type) || type.isEnumType() || type.hasRawClass(Object.class);
        if (scalar || isMap(type) || isCollection(type)) {
            return false;
        }
        try {
            JsonSerializer<Object> serializer = this.mapper.getSerializerProviderInstance().findValueSerializer(type);
            // 多态标记、对象 ID 和动态属性只扩展内容，不应抹掉普通字段的 SOAP QName。
            return serializer instanceof BeanSerializerBase && this.jacksonImplementation(serializer);
        } catch (JsonMappingException failure) {
            String reason = "Cannot inspect Jackson XML properties for " + type.toCanonical();
            throw new IllegalArgumentException(reason, failure);
        }
    }

    private boolean jacksonValue(JavaType type) {
        try {
            SerializationConfig serialization = this.bindings.getSerializationConfig();
            DeserializationConfig deserialization = this.bindings.getDeserializationConfig();
            BeanDescription bean = serialization.introspect(type);
            BeanDescription input = deserialization.introspect(type);
            AnnotationIntrospector writer = serialization.getAnnotationIntrospector();
            AnnotationIntrospector reader = deserialization.getAnnotationIntrospector();
            boolean dynamic = bean.findJsonValueAccessor() != null || bean.findAnyGetter() != null //
                    || input.findAnySetterAccessor() != null || bean.getObjectIdInfo() != null;
            boolean customized = writer.findSerializer(bean.getClassInfo()) != null //
                    || reader.findDeserializer(input.getClassInfo()) != null //
                    || writer.findSerializationConverter(bean.getClassInfo()) != null //
                    || reader.findDeserializationConverter(input.getClassInfo()) != null;
            JavaType refined = reader.refineDeserializationType(deserialization, input.getClassInfo(), type);
            JavaType serialized = writer.refineSerializationType(serialization, bean.getClassInfo(), type);
            if (dynamic || customized || !type.equals(refined) || !type.equals(serialized)) {
                return true;
            }
            for (BeanPropertyDefinition property : bean.findProperties()) {
                AnnotatedMember member = property.getPrimaryMember();
                if (member != null && writer.findUnwrappingNameTransformer(member) != null) {
                    return true;
                }
            }
            if (type.isEnumType()) {
                for (AnnotatedMethod method : input.getFactoryMethods()) {
                    JsonCreator.Mode creator = reader.findCreatorAnnotation(deserialization, method);
                    if (creator != null && creator != JsonCreator.Mode.DISABLED) {
                        return true;
                    }
                }
            }
            JsonFormat.Value format = writer.findFormat(bean.getClassInfo());
            JsonFormat.Value defaults = serialization.getDefaultPropertyFormat(type.getRawClass());
            if (this.hasFormat(format) || this.hasFormat(defaults)) {
                return true;
            }
            if (Date.class.isAssignableFrom(type.getRawClass()) //
                    && !serialization.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)) {
                return true;
            }
            if (type.isEnumType() && (serialization.isEnabled(SerializationFeature.WRITE_ENUMS_USING_INDEX) //
                    || serialization.isEnabled(SerializationFeature.WRITE_ENUMS_USING_TO_STRING) //
                    || deserialization.isEnabled(DeserializationFeature.READ_ENUMS_USING_TO_STRING))) {
                return true;
            }
            SerializerProvider provider = this.bindings.getSerializerProviderInstance();
            if (provider.findTypeSerializer(type) != null) {
                return true;
            }
            JsonSerializer<Object> serializer = provider.findValueSerializer(type);
            if (!this.jacksonImplementation(serializer)) {
                return true;
            }
            DeserializationContext prototype = this.bindings.getDeserializationContext();
            DefaultDeserializationContext context = (DefaultDeserializationContext) prototype;
            JsonDeserializer<Object> deserializer = context.createDummyInstance(deserialization) //
                    .findRootValueDeserializer(type);
            return deserializer != null && !this.jacksonImplementation(deserializer);
        } catch (JsonMappingException failure) {
            throw new IllegalArgumentException("Cannot inspect Jackson XML binding for " + type.toCanonical(), failure);
        }
    }

    private int jacksonPropertyBindings(List<AnnotatedMember> members, JavaType type) {
        SerializationConfig serialization = this.mapper.getSerializationConfig();
        DeserializationConfig deserialization = this.mapper.getDeserializationConfig();
        AnnotationIntrospector writer = serialization.getAnnotationIntrospector();
        AnnotationIntrospector reader = deserialization.getAnnotationIntrospector();
        int bindings = 0;
        for (AnnotatedMember member : members) {
            boolean value = writer.findSerializer(member) != null || reader.findDeserializer(member) != null //
                    || writer.findSerializationConverter(member) != null //
                    || reader.findDeserializationConverter(member) != null //
                    || writer.findPropertyTypeResolver(serialization, member, type) != null //
                    || this.hasFormat(writer.findFormat(member)) //
                    || writer.findUnwrappingNameTransformer(member) != null;
            boolean content = writer.findContentSerializer(member) != null //
                    || reader.findContentDeserializer(member) != null //
                    || writer.findSerializationContentConverter(member) != null //
                    || reader.findDeserializationContentConverter(member) != null;
            if (type.isContainerType()) {
                content |= writer.findPropertyContentTypeResolver(serialization, member, type) != null;
            }
            try {
                JavaType input = reader.refineDeserializationType(deserialization, member, type);
                JavaType output = writer.refineSerializationType(serialization, member, type);
                if (type.isContainerType()) {
                    value |= type.getRawClass() != input.getRawClass() || type.getRawClass() != output.getRawClass();
                    content |= !type.getContentType().equals(input.getContentType()) //
                            || !type.getContentType().equals(output.getContentType());
                } else {
                    value |= !type.equals(input) || !type.equals(output);
                }
            } catch (JsonMappingException failure) {
                throw new IllegalArgumentException("Cannot inspect Jackson XML property " + member.getName(), failure);
            }
            if (value) {
                bindings |= JACKSON_VALUE;
            }
            if (content) {
                bindings |= JACKSON_CONTENT;
            }
        }
        return bindings;
    }

    private boolean hasFormat(JsonFormat.Value format) {
        return format != null && !format.equals(JsonFormat.Value.empty());
    }

    private void requireJacksonConstructor(JavaType type) {
        try {
            DeserializationContext prototype = this.bindings.getDeserializationContext();
            DefaultDeserializationContext context = (DefaultDeserializationContext) prototype;
            JsonDeserializer<?> deserializer = context.createDummyInstance(this.bindings.getDeserializationConfig()) //
                    .findRootValueDeserializer(type);
            Set<JsonDeserializer<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (deserializer != null && visited.add(deserializer)) {
                if (deserializer instanceof BeanDeserializerBase bean) {
                    ValueInstantiator creator = bean.getValueInstantiator();
                    boolean objectCreator = creator.canCreateUsingDefault() || creator.canCreateFromObjectWith() //
                            || creator.canCreateUsingDelegate() || creator.canCreateUsingArrayDelegate();
                    if (!objectCreator) {
                        throw this.unsupported(type, "POJOs require a Jackson object creator or custom deserializer");
                    }
                    return;
                }
                deserializer = deserializer.getDelegatee();
            }
        } catch (JsonMappingException failure) {
            throw new IllegalArgumentException("Cannot inspect Jackson XML creator for " + type.toCanonical(), failure);
        }
    }

    private boolean jacksonImplementation(Object handler) {
        String name = handler.getClass().getName();
        return name.startsWith("com.fasterxml.jackson.databind.") //
                || name.startsWith("com.fasterxml.jackson.dataformat.xml.");
    }

    /** encoded 保留 id/href 的直接对象图绑定，不把 Jackson 的构造或多态能力静默套入。 */
    public void requireEncodedType(JavaType type) {
        String reason = this.encodedTypeIssue(type);
        if (reason != null) {
            throw this.unsupported(type, reason);
        }
    }

    /** 发布准备只拒绝无法执行的 Java 绑定，属性映射本身的注解冲突仍然报错。 */
    public boolean supportsEncodedType(JavaType type) {
        return this.encodedTypeIssue(type) == null;
    }

    private String encodedTypeIssue(JavaType type) {
        if (this.isJacksonValue(type)) {
            return "SOAP encoded does not support custom Jackson value bindings";
        }
        boolean scalar = isScalar(type) || type.isEnumType() || type.hasRawClass(Object.class);
        if (scalar || isMap(type) || isCollection(type)) {
            return null;
        }
        DeserializationConfig config = this.mapper.getDeserializationConfig();
        BeanDescription bean = config.introspect(type);
        // encoded 先判断自己要求的无参构造，不进入可能直接拒绝该类型的 Jackson 对象创建校验。
        if (bean.findDefaultConstructor() == null) {
            return "SOAP encoded POJOs require a no-argument constructor";
        }
        SoapXmlType mapping = this.type(type);
        AnnotationIntrospector introspector = config.getAnnotationIntrospector();
        for (AnnotatedConstructor constructor : bean.getConstructors()) {
            JsonCreator.Mode creator = introspector.findCreatorAnnotation(config, constructor);
            if (constructor.getParameterCount() > 0 && creator != null && creator != JsonCreator.Mode.DISABLED) {
                return "SOAP encoded does not support Jackson property or delegating creators";
            }
        }
        for (AnnotatedMethod method : bean.getFactoryMethods()) {
            JsonCreator.Mode creator = introspector.findCreatorAnnotation(config, method);
            if (creator != null && creator != JsonCreator.Mode.DISABLED) {
                return "SOAP encoded does not support Jackson factory creators";
            }
        }
        for (SoapXmlProperty property : mapping.getProperties()) {
            if (property.isJacksonValue() || !property.isReadable() || !property.isWritable()) {
                return "SOAP encoded requires ordinary readable and writable properties";
            }
        }
        return null;
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
