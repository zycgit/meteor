/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlType;
import org.w3c.dom.*;

/** 按共享 XML 映射读写 literal 值，QName 与 Java 属性名称分别匹配。 */
final class SoapLiteralValueCodec {
    private static final String                XSI_NAMESPACE                = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final int                   MAX_DEPTH                    = 128;
    private static final Set<String>           CONTROL_ATTRIBUTE_NAMESPACES = Set.of( // XML and SOAP control attributes
            XMLConstants.XML_NS_URI, XMLConstants.XMLNS_ATTRIBUTE_NS_URI, XSI_NAMESPACE, // XML
            "http://schemas.xmlsoap.org/soap/envelope/", "http://www.w3.org/2003/05/soap-envelope", // Envelope
            "http://schemas.xmlsoap.org/soap/encoding/", "http://www.w3.org/2003/05/soap-encoding");
    private final        XmlMapper             mapper;
    private final        SoapXmlMapping        mapping;
    private final        SoapJacksonValueCodec jackson;

    SoapLiteralValueCodec(SoapXmlMapping mapping) {
        this.mapper = mapping.mapper();
        this.mapping = mapping;
        this.jackson = new SoapJacksonValueCodec(mapping.bindings());
    }

    Object read(Element element, Type type) throws Exception {
        return this.readValue(element, this.mapper.constructType(type), 0);
    }

    void write(Element target, Type type, Object value, Map<Element, byte[]> binaries) throws Exception {
        this.writeValue(target, this.mapper.constructType(type), value, binaries, new IdentityHashMap<>(), 0);
    }

    private Object readValue(Element element, JavaType type, int depth) throws Exception {
        return this.readValue(element, type, depth, true);
    }

    private Object readValue(Element element, JavaType type, int depth, boolean mapEntryNillable) throws Exception {
        this.requireDepth(depth);
        if (this.nil(element)) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("SOAP XML nil cannot map to a primitive: " + type);
            }
            if (!this.children(element).isEmpty() || !element.getTextContent().isBlank()) {
                throw new IllegalArgumentException("SOAP XML nil element cannot contain a value");
            }
            return null;
        }

        if (this.mapping.isJacksonValue(type)) {
            return this.jackson.read(type, this.readBoundValue(element, type, depth, false));
        }
        if (type.hasRawClass(Object.class)) {
            return this.readAny(element, depth);
        }
        if (SoapXmlMapping.isScalar(type) || type.isEnumType()) {
            if (!this.children(element).isEmpty()) {
                throw new IllegalArgumentException("SOAP XML scalar must contain only text: " + type);
            }
            return this.scalarValue(element.getTextContent(), type);
        }
        if (SoapXmlMapping.isCollection(type)) {
            if (this.hasText(element)) {
                throw new IllegalArgumentException("SOAP XML collection cannot contain direct text: " + type);
            }
            List<Object> values = new ArrayList<>();
            for (Element item : this.children(element)) {
                // 条目 QName 由生成或外部 XSD 决定，Java 泛型只确定其值类型。
                values.add(this.readValue(item, type.getContentType(), depth + 1));
            }
            return this.collection(type, values);
        }
        if (SoapXmlMapping.isMap(type)) {
            this.mapping.type(type);
            if (this.hasText(element)) {
                throw new IllegalArgumentException("SOAP XML Map cannot contain direct text: " + type);
            }
            Map<String, Object> values = this.newMap(type);
            String namespace = this.namespace(element.getNamespaceURI());
            for (Element entry : this.children(element)) {
                String key = entry.getLocalName() == null ? entry.getNodeName() : entry.getLocalName();
                this.requireMapKey(element, key, false);
                if (!StringUtils.equals(namespace, this.namespace(entry.getNamespaceURI()))) {
                    throw new IllegalArgumentException("Unexpected SOAP XML Map entry namespace: " + key);
                }
                if (values.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate SOAP XML Map key: " + key);
                }
                this.requireNillable(entry, mapEntryNillable, new QName(namespace, key));
                JavaType valueType = type.getContentType();
                if (SoapXmlMapping.isScalar(valueType) || valueType.isEnumType()) {
                    this.requireScalarAttributes(entry);
                }
                Object value = this.readValue(entry, valueType, depth + 1);
                if (value == null && !this.nil(entry)) {
                    throw new IllegalArgumentException("SOAP XML Map value has no valid content: " + key);
                }
                values.put(key, value);
            }
            return values;
        }

        return this.jackson.read(type, this.readBoundValue(element, type, depth, false));
    }

    private Object readAny(Element element, int depth) {
        String lexicalType = element.getAttributeNS(XSI_NAMESPACE, "type").trim();
        if (element.hasAttributeNS(XSI_NAMESPACE, "type")) {
            if (StringUtils.isEmpty(lexicalType)) {
                throw new IllegalArgumentException("SOAP XML xsi:type cannot be empty");
            }
            int colon = lexicalType.indexOf(':');
            String prefix = colon < 0 ? null : lexicalType.substring(0, colon);
            String local = colon < 0 ? lexicalType : lexicalType.substring(colon + 1);
            String namespace = element.lookupNamespaceURI(prefix);
            if (colon >= 0 && namespace == null) {
                throw new IllegalArgumentException("Unbound SOAP XML xsi:type prefix: " + lexicalType);
            }
            // QName 值不会经过 DOM 的名称校验，需要在查找绑定前验证词法形式。
            try {
                element.getOwnerDocument().createElementNS(namespace, lexicalType);
            } catch (DOMException failure) {
                throw new IllegalArgumentException("Invalid SOAP XML xsi:type: " + lexicalType, failure);
            }
            if (StringUtils.equals(XMLConstants.W3C_XML_SCHEMA_NS_URI, namespace)) {
                Class<?> scalar = this.anyScalarClass(local);
                if (scalar != null) {
                    this.requireScalarAttributes(element);
                    if (!this.children(element).isEmpty()) {
                        throw new IllegalArgumentException("SOAP XML scalar must contain only text: " + lexicalType);
                    }
                    Object value = this.scalarValue(element.getTextContent(), this.mapper.constructType(scalar));
                    if (value == null) {
                        throw new IllegalArgumentException("Invalid SOAP XML scalar content: " + lexicalType);
                    }
                    return value;
                }
            }
            // 未知 xsi:type 只是 XML QName，绝不按报文中的名称加载 Java 类。
            return this.copyAnyElement(element, depth);
        }
        if (this.hasAnyAttributes(element)) {
            return this.copyAnyElement(element, depth);
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.TEXT_NODE) {
                return this.copyAnyElement(element, depth);
            }
        }
        return element.getTextContent();
    }

    private String anyScalarType(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (type.isEnumType() || raw == String.class || raw == Character.class || raw == char[].class) {
            return "string";
        }
        if (raw == Boolean.class) {
            return "boolean";
        }
        if (raw == Byte.class) {
            return "byte";
        }
        if (raw == Short.class) {
            return "short";
        }
        if (raw == Integer.class) {
            return "int";
        }
        if (raw == Long.class || raw == Date.class) {
            return "long";
        }
        if (raw == BigInteger.class) {
            return "integer";
        }
        if (raw == BigDecimal.class) {
            return "decimal";
        }
        if (raw == Float.class) {
            return "float";
        }
        if (raw == Double.class) {
            return "double";
        }
        if (raw == byte[].class) {
            return "base64Binary";
        }
        return null;
    }

    private Class<?> anyScalarClass(String local) {
        return switch (local) {
            case "string" -> String.class;
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "integer" -> BigInteger.class;
            case "decimal" -> BigDecimal.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "base64Binary" -> byte[].class;
            default -> null;
        };
    }

    private boolean hasAnyAttributes(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            boolean namespace = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNamespaceURI());
            boolean instanceAttribute = StringUtils.equals(XSI_NAMESPACE, attribute.getNamespaceURI());
            boolean nil = instanceAttribute && StringUtils.equals("nil", attribute.getLocalName());
            if (!namespace && !nil) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBusinessAttributes(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            if (!this.controlAttribute((Attr) attributes.item(index))) {
                return true;
            }
        }
        return false;
    }

    private void requireScalarAttributes(Element element) {
        if (this.hasBusinessAttributes(element)) {
            throw new IllegalArgumentException("SOAP XML scalar cannot contain business attributes");
        }
    }

    private Element copyAnyElement(Element source, int depth) {
        // 在深复制前校验整个子树，防止 anyType 跳过递归边界。
        this.requireElementDepth(source, depth);
        Element copy = (Element) SoapXmlUtils.newDocument().importNode(source, true);
        copy.getOwnerDocument().appendChild(copy);
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (Node node = source; node instanceof Element; node = node.getParentNode()) {
            // DOM 元素自身的 QName 也建立绑定，不一定存在显式 xmlns 属性。
            if (!StringUtils.isEmpty(node.getNamespaceURI())) {
                String prefix = node.getPrefix();
                String declaration = StringUtils.isEmpty(prefix) ? "xmlns" : "xmlns:" + prefix;
                namespaces.putIfAbsent(declaration, node.getNamespaceURI());
            }
            NamedNodeMap attributes = node.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNamespaceURI())) {
                    namespaces.putIfAbsent(attribute.getName(), attribute.getValue());
                }
            }
        }
        // 未声明默认 namespace 也不能在回写时继承目标文档的默认 namespace。
        namespaces.putIfAbsent("xmlns", this.namespace(source.lookupNamespaceURI(null)));
        namespaces.forEach((name, uri) -> copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, name, uri));
        return copy;
    }

    private void writeAnyElement(Element target, Element source, int depth) {
        Element copy = this.copyAnyElement(source, depth);
        String targetNamespace = this.namespace(target.getNamespaceURI());
        String sourceDefault = this.namespace(copy.lookupNamespaceURI(null));
        if (StringUtils.isEmpty(targetNamespace) && !StringUtils.isEmpty(sourceDefault)) {
            String message = "SOAP XML anyType cannot preserve a default namespace on an unqualified target element";
            throw new IllegalArgumentException(message);
        }
        String targetPrefix = target.getPrefix();
        String sourceBinding = this.namespace(copy.lookupNamespaceURI(targetPrefix));
        if (!StringUtils.isEmpty(targetNamespace) && !StringUtils.equals(targetNamespace, sourceBinding)) {
            // 源内容可能含未知 QName 文本，只为目标载体换前缀，不改写源命名空间。
            targetPrefix = "value";
            int suffix = 1;
            while (copy.lookupNamespaceURI(targetPrefix) != null || target.lookupNamespaceURI(targetPrefix) != null) {
                targetPrefix = "value" + suffix++;
            }
            target.setPrefix(targetPrefix);
        }
        NamedNodeMap attributes = copy.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNamespaceURI())) {
                target.setAttributeNS(attribute.getNamespaceURI(), attribute.getName(), attribute.getValue());
            } else {
                QName name = this.attributeName(attribute);
                SoapXmlOutputUtils.attribute(target, name, attribute.getName(), attribute.getValue());
            }
        }
        if (!StringUtils.isEmpty(targetPrefix)) {
            target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + targetPrefix, targetNamespace);
        }
        if (!target.hasAttributeNS(XSI_NAMESPACE, "type")) {
            // DOM 本身是显式开放结构，空节点也不能在接收端退化为空字符串。
            String schemaPrefix = this.prefix(target, XMLConstants.W3C_XML_SCHEMA_NS_URI);
            String xsiPrefix = this.prefix(target, XSI_NAMESPACE);
            target.setAttributeNS(XSI_NAMESPACE, xsiPrefix + ":type", schemaPrefix + ":anyType");
        }
        // 保留 mixed content、CDATA 与注释，不能仅遍历 Element。
        for (Node child = copy.getFirstChild(); child != null; child = child.getNextSibling()) {
            target.appendChild(target.getOwnerDocument().importNode(child, true));
        }
    }

    private void requireElementDepth(Element source, int depth) {
        this.requireDepth(depth);
        for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
            short kind = node.getNodeType();
            if (kind == Node.PROCESSING_INSTRUCTION_NODE || kind == Node.ENTITY_REFERENCE_NODE) {
                String message = "SOAP XML anyType cannot contain processing instructions or entities";
                throw new IllegalArgumentException(message);
            }
            if (node instanceof Element child) {
                this.requireElementDepth(child, depth + 1);
            }
        }
    }

    private SoapJacksonValue readBoundValue(Element element, JavaType type, int depth, boolean opaque) //
            throws Exception {
        this.requireDepth(depth);
        if (this.nil(element)) {
            this.readValue(element, type, depth);
            return new SoapJacksonValue(null, null);
        }
        if (opaque) {
            return this.readGenericValue(element, type, null, depth);
        }
        type = SoapJacksonUtils.resolvedType(this.mapper, element, type, null);
        boolean jacksonShape = this.mapping.isJacksonValue(type);
        if (jacksonShape) {
            SoapJacksonValue generic = this.readGenericValue(element, type, null, depth);
            if (!generic.object() || this.mapping.type(type).getProperties().isEmpty()) {
                return generic;
            }
        }
        if (type.hasRawClass(Object.class)) {
            return new SoapJacksonValue(this.readAny(element, depth), null);
        }
        if (SoapXmlMapping.isScalar(type) || type.isEnumType()) {
            if (!this.children(element).isEmpty()) {
                throw new IllegalArgumentException("SOAP XML scalar must contain only text: " + type);
            }
            return this.readBoundScalar(element.getTextContent(), type);
        }
        if (SoapXmlMapping.isCollection(type)) {
            if (this.hasText(element)) {
                throw new IllegalArgumentException("SOAP XML collection cannot contain direct text: " + type);
            }
            List<SoapJacksonValue> values = new ArrayList<>();
            for (Element child : this.children(element)) {
                values.add(this.readBoundValue(child, type.getContentType(), depth + 1, false));
            }
            return new SoapJacksonValue(values, null);
        }
        if (SoapXmlMapping.isMap(type)) {
            this.mapping.type(type);
            if (this.hasText(element)) {
                throw new IllegalArgumentException("SOAP XML Map cannot contain direct text: " + type);
            }
            Map<String, SoapJacksonValue> values = new LinkedHashMap<>();
            for (Element child : this.children(element)) {
                String key = this.localName(child);
                this.requireMapKey(element, key, false);
                String namespace = this.namespace(element.getNamespaceURI());
                if (!StringUtils.equals(namespace, this.namespace(child.getNamespaceURI()))) {
                    throw new IllegalArgumentException("Unexpected SOAP XML Map entry namespace: " + key);
                }
                if (values.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate SOAP XML Map key: " + key);
                }
                JavaType valueType = type.getContentType();
                if (SoapXmlMapping.isScalar(valueType) || valueType.isEnumType()) {
                    this.requireScalarAttributes(child);
                }
                values.put(key, this.readBoundValue(child, valueType, depth + 1, false));
            }
            return new SoapJacksonValue(values, null);
        }
        SoapXmlType bean = this.mapping.type(type);
        this.requireKnownNames(element, bean);
        Map<String, SoapJacksonValue> fields = new LinkedHashMap<>();
        Map<String, SoapJacksonValue> mappedFields = new LinkedHashMap<>();
        {
            // 未映射的 XML 字段也交给最终 Jackson reader 决定忽略、拒绝或收进 any-setter。
            SoapJacksonValue generic = this.readGenericValue(element, depth);
            if (generic.object()) {
                fields.putAll(generic.fields());
            }
            for (SoapXmlProperty property : bean.getProperties()) {
                QName name = property.getWrapper() == null ? property.getName() : property.getWrapper();
                fields.remove(name.getLocalPart());
                property.getAliases().forEach(fields::remove);
                if (property.isText()) {
                    fields.remove("");
                }
            }
        }
        for (SoapXmlProperty property : bean.getProperties()) {
            SoapJacksonValue value;
            if (property.isAttribute()) {
                value = this.readBoundAttribute(element, property, bean);
            } else if (property.isText()) {
                if (!this.children(element).isEmpty()) {
                    throw new IllegalArgumentException("SOAP XML text value cannot contain child elements");
                }
                value = this.readBoundScalar(element.getTextContent(), property.getJavaType());
            } else {
                value = this.readBoundProperty(element, property, depth);
            }
            if (value != null) {
                if (property.isAttribute() && property.getWrapper() == null && value.object()) {
                    value.fields().keySet().forEach(fields::remove);
                }
                String name = property.getBindingName();
                if (property.isText() && property.getBindingMember() != null) {
                    JacksonXmlText text = property.getBindingMember().getAnnotation(JacksonXmlText.class);
                    if (text != null && text.value()) {
                        // XmlBeanDeserializerModifier 使用空名称接收原生 XML 文本属性。
                        name = "";
                    }
                }
                mappedFields.put(name, value);
            }
        }
        // 动态属性先从原始 XML 字段中移除，再合入绑定结果，避免不同 QName 的同名属性相互覆盖。
        fields.putAll(mappedFields);
        return new SoapJacksonValue(fields, null);
    }

    private SoapJacksonValue readBoundScalar(String text, JavaType type) {
        Class<?> raw = ClassUtils.primitiveToWrapper(type.getRawClass());
        Object value = text;
        String normalized = text.trim();
        if (raw == byte[].class) {
            value = Base64.getDecoder().decode(text.replaceAll("[ \t\r\n]", ""));
        } else if (raw == Date.class || raw == Long.class) {
            value = Long.valueOf(normalized);
        } else if (raw == Byte.class) {
            value = Byte.valueOf(normalized);
        } else if (raw == Short.class) {
            value = Short.valueOf(normalized);
        } else if (raw == Integer.class) {
            value = Integer.valueOf(normalized);
        } else if (raw == BigInteger.class) {
            value = new BigInteger(normalized);
        } else if (raw == BigDecimal.class) {
            value = new BigDecimal(normalized);
        } else if (raw == Float.class || raw == Double.class) {
            if (StringUtils.equals(normalized, "INF")) {
                normalized = "Infinity";
            } else if (StringUtils.equals(normalized, "-INF")) {
                normalized = "-Infinity";
            }
            value = raw == Float.class ? Float.valueOf(normalized) : Double.valueOf(normalized);
        } else if (raw == Boolean.class) {
            if (StringUtils.equals(normalized, "true") || StringUtils.equals(normalized, "1")) {
                value = true;
            } else if (StringUtils.equals(normalized, "false") || StringUtils.equals(normalized, "0")) {
                value = false;
            } else {
                throw new IllegalArgumentException("Invalid XML Schema boolean: " + text);
            }
        }
        return new SoapJacksonValue(value, null);
    }

    private SoapJacksonValue readBoundAttribute(Element source, SoapXmlProperty property, SoapXmlType bean) {
        if (SoapXmlMapping.isMap(property.getJavaType())) {
            Map<String, SoapJacksonValue> values = new LinkedHashMap<>();
            Set<QName> statics = this.staticAttributes(bean);
            String namespace = property.getName().getNamespaceURI();
            NamedNodeMap attributes = source.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                QName name = this.attributeName(attribute);
                boolean mappedNamespace = StringUtils.equals(namespace, name.getNamespaceURI());
                if (this.controlAttribute(attribute) || statics.contains(name) || !mappedNamespace) {
                    continue;
                }
                this.requireMapKey(source, name.getLocalPart(), true);
                JavaType content = property.getJavaType().getContentType();
                SoapJacksonValue value = this.readBoundScalar(attribute.getValue(), content);
                if (value.value() == null) {
                    throw new IllegalArgumentException("SOAP XML Map attribute has no valid scalar content: " + name);
                }
                values.put(name.getLocalPart(), value);
            }
            if (values.isEmpty() && property.isRequired()) {
                throw this.missing(property);
            }
            return new SoapJacksonValue(values, null);
        }
        QName name = property.getName();
        String namespace = StringUtils.isEmpty(name.getNamespaceURI()) ? null : name.getNamespaceURI();
        Attr attribute = source.getAttributeNodeNS(namespace, name.getLocalPart());
        for (String alias : property.getAliases()) {
            Attr alternative = source.getAttributeNodeNS(namespace, alias);
            if (alternative != null) {
                if (attribute != null) {
                    throw new IllegalArgumentException("Duplicate SOAP XML attribute: " + name);
                }
                attribute = alternative;
            }
        }
        if (attribute != null) {
            if (property.isJacksonValue()) {
                return new SoapJacksonValue(attribute.getValue(), null);
            }
            return this.readBoundScalar(attribute.getValue(), property.getJavaType());
        }
        if (property.isRequired()) {
            throw this.missing(property);
        }
        return null;
    }

    private SoapJacksonValue readBoundProperty(Element source, SoapXmlProperty property, int depth) throws Exception {
        JavaType type = property.getJavaType();
        Element scope = source;
        if (property.getWrapper() != null) {
            scope = this.single(source, property.getWrapper());
            if (scope == null) {
                if (property.isWrapperRequired()) {
                    throw new IllegalArgumentException("Required SOAP XML wrapper is absent: " + property.getWrapper());
                }
                return null;
            }
            this.requireNillable(scope, property.isWrapperNillable(), property.getWrapper());
            if (this.nil(scope)) {
                this.readValue(scope, type, depth + 1);
                return new SoapJacksonValue(null, null);
            }
        }
        List<Element> items = this.matching(scope, property.getName());
        for (String alias : property.getAliases()) {
            QName name = new QName(property.getName().getNamespaceURI(), alias);
            items.addAll(this.matching(scope, name));
        }
        if (property.getWrapper() != null && items.size() != this.children(scope).size()) {
            throw new IllegalArgumentException("Unexpected SOAP XML wrapped element in " + property.getWrapper());
        }
        if (!SoapXmlMapping.isCollection(type) || property.isJacksonValue() && !property.isJacksonContent()) {
            if (items.size() > 1) {
                throw new IllegalArgumentException("Duplicate SOAP XML property: " + property.getName());
            }
            if (items.isEmpty()) {
                if (property.isRequired()) {
                    throw this.missing(property);
                }
                return null;
            }
            Element value = items.get(0);
            this.requireNillable(value, property.isNillable(), property.getName());
            if (SoapXmlMapping.isMap(type) && !property.isNillable()) {
                for (Element entry : this.children(value)) {
                    this.requireNillable(entry, false, property.getName());
                }
            }
            if (SoapXmlMapping.isMap(type) && property.isJacksonContent()) {
                return this.readBoundMapContent(value, property, depth + 1);
            }
            JavaType resolved = SoapJacksonUtils.resolvedType(this.mapper, value, type, property);
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, type, property);
            boolean nativeShape = property.isJacksonValue() && discriminator == null && resolved.equals(type);
            SoapJacksonValue decoded = nativeShape //
                    ? this.readGenericValue(value, resolved, property, depth + 1) //
                    : this.readBoundValue(value, resolved, depth + 1, false);
            this.readDiscriminator(value, decoded, discriminator);
            return decoded;
        }
        if (items.isEmpty() && property.isRequired()) {
            throw this.missing(property);
        }
        if (items.isEmpty() && property.getWrapper() == null) {
            return null;
        }
        List<SoapJacksonValue> values = new ArrayList<>();
        for (Element item : items) {
            this.requireNillable(item, property.isNillable(), property.getName());
            JavaType content = type.getContentType();
            JavaType resolved = SoapJacksonUtils.resolvedType(this.mapper, item, content, property);
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, content, property);
            boolean nativeShape = property.isJacksonContent() && discriminator == null && resolved.equals(content);
            SoapJacksonValue decoded = nativeShape //
                    ? this.readGenericValue(item, resolved, property, depth + 1) //
                    : this.readBoundValue(item, resolved, depth + 1, false);
            this.readDiscriminator(item, decoded, discriminator);
            values.add(decoded);
        }
        return new SoapJacksonValue(values, null);
    }

    private SoapJacksonValue readBoundMapContent(Element element, SoapXmlProperty property, int depth) //
            throws Exception {
        if (this.nil(element)) {
            return this.readBoundValue(element, property.getJavaType(), depth, false);
        }
        if (this.hasText(element)) {
            throw new IllegalArgumentException("SOAP XML Map cannot contain direct text: " + property.getJavaType());
        }
        Map<String, SoapJacksonValue> fields = new LinkedHashMap<>();
        JavaType content = property.getJavaType().getContentType();
        String namespace = this.namespace(element.getNamespaceURI());
        for (Element entry : this.children(element)) {
            String key = this.requireMapKey(element, this.localName(entry), false);
            if (!StringUtils.equals(namespace, this.namespace(entry.getNamespaceURI()))) {
                throw new IllegalArgumentException("Unexpected SOAP XML Map entry namespace: " + key);
            }
            if (fields.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate SOAP XML Map key: " + key);
            }
            JavaType resolved = SoapJacksonUtils.resolvedType(this.mapper, entry, content, property);
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, content, property);
            boolean nativeShape = discriminator == null && resolved.equals(content);
            SoapJacksonValue value = nativeShape //
                    ? this.readGenericValue(entry, resolved, property, depth + 1) //
                    : this.readBoundValue(entry, resolved, depth + 1, false);
            this.readDiscriminator(entry, value, discriminator);
            fields.put(key, value);
        }
        return new SoapJacksonValue(fields, null);
    }

    private SoapJacksonValue readGenericValue(Element element, JavaType type, SoapXmlProperty property, int depth) //
            throws Exception {
        if (this.nil(element)) {
            return new SoapJacksonValue(null, null);
        }
        boolean array = SoapJacksonUtils.arrayShape(this.mapper, type, property);
        if (!array) {
            return this.readGenericValue(element, depth);
        }
        if (this.hasText(element)) {
            if (this.children(element).isEmpty()) {
                // 自定义集合读写器可以选择文本形状，最终 Jackson reader 负责验证该输入。
                return this.readGenericValue(element, depth);
            }
            throw new IllegalArgumentException("SOAP Jackson array cannot contain direct text");
        }
        List<SoapJacksonValue> items = new ArrayList<>();
        for (Element child : this.children(element)) {
            items.add(this.readGenericValue(child, depth + 1));
        }
        return new SoapJacksonValue(items, null);
    }

    private void readDiscriminator(Element element, SoapJacksonValue decoded, String property) {
        if (property == null || !decoded.object()) {
            return;
        }
        SoapJacksonValue generic = this.readGenericValue(element, 0);
        if (generic.object() && generic.fields().containsKey(property)) {
            decoded.fields().put(property, generic.fields().get(property));
        }
    }

    private SoapJacksonValue readGenericValue(Element element, int depth) {
        this.requireDepth(depth);
        if (this.nil(element)) {
            return new SoapJacksonValue(null, null);
        }
        List<Element> children = this.children(element);
        if (children.isEmpty() && !this.hasBusinessAttributes(element)) {
            return new SoapJacksonValue(element.getTextContent(), null);
        }
        Map<String, SoapJacksonValue> fields = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            if (!this.controlAttribute(attribute)) {
                String name = attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
                fields.put(name, new SoapJacksonValue(attribute.getValue(), null));
            }
        }
        for (Element child : children) {
            String name = this.localName(child);
            SoapJacksonValue value = this.readGenericValue(child, depth + 1);
            SoapJacksonValue previous = fields.get(name);
            if (previous == null) {
                fields.put(name, value);
            } else if (previous.array()) {
                previous.items().add(value);
            } else {
                List<SoapJacksonValue> repeated = new ArrayList<>();
                repeated.add(previous);
                repeated.add(value);
                fields.put(name, new SoapJacksonValue(repeated, null));
            }
        }
        if (this.hasText(element)) {
            fields.put("", new SoapJacksonValue(element.getTextContent(), null));
        }
        return new SoapJacksonValue(fields, null);
    }

    private String localName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private void writeValue(Element target, JavaType type, Object value, //
            Map<Element, byte[]> binaries, IdentityHashMap<Object, Boolean> active, int depth) throws Exception {
        this.writeValue(target, type, value, binaries, active, depth, true);
    }

    private void writeValue(Element target, JavaType type, Object value, //
            Map<Element, byte[]> binaries, IdentityHashMap<Object, Boolean> active, // output state
            int depth, boolean mapEntryNillable) throws Exception {
        this.requireDepth(depth);
        if (value == null) {
            this.writeNil(target);
            return;
        }
        this.requireValueType(type, value);
        if (this.mapping.isJacksonValue(type)) {
            this.writeBoundValue(target, type, this.jackson.write(type, value), binaries, depth, false);
            return;
        }
        if (type.hasRawClass(Object.class)) {
            if (value instanceof Element element) {
                this.writeAnyElement(target, element, depth);
                return;
            }
            Class<?> actualClass = value instanceof Enum<?> enumeration // 枚举常量可能拥有匿名子类。
                    ? enumeration.getDeclaringClass() : value.getClass();
            if (value instanceof Map<?, ?>) {
                type = this.mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            } else {
                type = this.mapper.constructType(actualClass);
            }
            if (!this.mapping.isJacksonValue(type)) {
                String scalarType = this.anyScalarType(type);
                String xmlType = scalarType == null ? "anyType" : scalarType;
                String prefix = this.prefix(target, XMLConstants.W3C_XML_SCHEMA_NS_URI);
                String xsiPrefix = this.prefix(target, XSI_NAMESPACE);
                target.setAttributeNS(XSI_NAMESPACE, xsiPrefix + ":type", prefix + ":" + xmlType);
            }
        }
        if (SoapXmlMapping.isScalar(type) || type.isEnumType()) {
            target.setTextContent(this.scalarText(value));
            if (value instanceof byte[] bytes && binaries != null) {
                binaries.put(target, bytes.clone());
            }
            return;
        }
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("SOAP literal cannot encode circular references: " + type);
        }
        try {
            if (SoapXmlMapping.isCollection(type)) {
                for (Object item : this.items(value)) {
                    Element child = this.append(target, new QName("item"));
                    this.writeValue(child, type.getContentType(), item, binaries, active, depth + 1);
                }
                return;
            }
            if (SoapXmlMapping.isMap(type)) {
                this.mapping.type(type);
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    String key = this.requireMapKey(target, entry.getKey(), false);
                    if (entry.getValue() == null && !mapEntryNillable) {
                        throw new IllegalArgumentException("SOAP XML Map entry is not nillable: " + key);
                    }
                    JavaType valueType = type.getContentType();
                    Element child = this.append(target, new QName(this.namespace(target.getNamespaceURI()), key));
                    this.writeValue(child, valueType, entry.getValue(), binaries, active, depth + 1);
                }
                return;
            }

            SoapJacksonValue bound = this.jackson.write(type, value);
            this.writeBoundValue(target, type, bound, binaries, depth, false);
        } finally {
            active.remove(value);
        }
    }

    private void writeBoundValue(Element target, JavaType type, SoapJacksonValue node, //
            Map<Element, byte[]> binaries, int depth, boolean opaque) throws Exception {
        this.writeBoundValue(target, type, node, binaries, depth, opaque, null);
    }

    private void writeBoundValue(Element target, JavaType type, SoapJacksonValue node, //
            Map<Element, byte[]> binaries, int depth, boolean opaque, String discriminator) throws Exception {
        this.requireDepth(depth);
        Object value = node.value();
        if (value == null) {
            this.writeNil(target);
            return;
        }
        if (value instanceof Element element) {
            this.writeAnyElement(target, element, depth);
            return;
        }
        if (opaque || this.mapping.isJacksonValue(type) && this.mapping.type(type).getProperties().isEmpty()) {
            this.writeGenericValue(target, node, binaries, depth);
            return;
        }
        if (type.hasRawClass(Object.class)) {
            Object actual = node.source() == null ? value : node.source();
            if (actual instanceof Map<?, ?>) {
                type = this.mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            } else if (node.array()) {
                type = this.mapper.getTypeFactory().constructCollectionType(List.class, Object.class);
            } else {
                type = this.mapper.constructType(actual.getClass());
            }
            if (discriminator == null && !this.mapping.isJacksonValue(type)) {
                String scalarType = this.anyScalarType(type);
                String xmlType = scalarType == null ? "anyType" : scalarType;
                String prefix = this.prefix(target, XMLConstants.W3C_XML_SCHEMA_NS_URI);
                String xsiPrefix = this.prefix(target, XSI_NAMESPACE);
                target.setAttributeNS(XSI_NAMESPACE, xsiPrefix + ":type", prefix + ":" + xmlType);
            }
        }
        if (!node.object() && !node.array()) {
            target.setTextContent(this.scalarText(value));
            if (value instanceof byte[] bytes && binaries != null) {
                binaries.put(target, bytes.clone());
            }
            return;
        }
        if (node.array() && !SoapXmlMapping.isCollection(type)) {
            // @JsonFormat(ARRAY) 等配置已由整个 DTO 的 Jackson writer 决定形状。
            this.writeGenericValue(target, node, binaries, depth);
            return;
        }
        if (SoapXmlMapping.isCollection(type)) {
            for (SoapJacksonValue item : node.items()) {
                Element child = this.append(target, new QName("item"));
                this.writeBoundValue(child, type.getContentType(), item, binaries, depth + 1, false);
            }
            return;
        }
        if (SoapXmlMapping.isMap(type)) {
            if (node.source() instanceof Map<?, ?> original) {
                for (Object key : original.keySet()) {
                    this.requireMapKey(target, key, false);
                }
            }
            for (Map.Entry<String, SoapJacksonValue> entry : node.fields().entrySet()) {
                String key = this.requireMapKey(target, entry.getKey(), false);
                QName name = new QName(this.namespace(target.getNamespaceURI()), key);
                Element child = this.append(target, name);
                this.writeBoundValue(child, type.getContentType(), entry.getValue(), binaries, depth + 1, false);
            }
            return;
        }
        String typeProperty = SoapJacksonUtils.typeProperty(this.mapper, type, null);
        if ((discriminator != null || typeProperty != null) && node.source() != null) {
            type = this.mapper.getTypeFactory().constructSpecializedType(type, node.source().getClass());
        }
        SoapXmlType bean = this.mapping.type(type);
        if (bean.isJacksonValue() && bean.getProperties().isEmpty()) {
            this.writeGenericValue(target, node, binaries, depth);
            return;
        }
        if (discriminator == null) {
            discriminator = SoapJacksonUtils.typeProperty(this.mapper, type, null);
        }
        Set<String> mapped = new HashSet<>();
        if (discriminator != null && node.fields().containsKey(discriminator)) {
            this.writeBoundAttributeValue(target, new QName(discriminator), node.fields().get(discriminator));
            mapped.add(discriminator);
        }
        for (SoapXmlProperty property : bean.getProperties()) {
            if (!mapped.add(property.getBindingName())) {
                continue;
            }
            SoapJacksonValue propertyValue = node.fields().get(property.getBindingName());
            if (propertyValue == null) {
                if (property.isRequired()) {
                    throw this.missing(property);
                }
                continue;
            }
            if (property.isAttribute()) {
                this.writeBoundAttribute(target, property, propertyValue, bean);
            } else if (property.isText()) {
                if (propertyValue.value() != null) {
                    this.writeGenericValue(target, propertyValue, binaries, depth + 1);
                }
            } else {
                this.writeBoundProperty(target, property, propertyValue, binaries, depth);
            }
        }
        for (Map.Entry<String, SoapJacksonValue> entry : node.fields().entrySet()) {
            if (!mapped.contains(entry.getKey())) {
                String name = this.requireMapKey(target, entry.getKey(), false);
                this.writeGenericValue(this.append(target, new QName(name)), entry.getValue(), binaries, depth + 1);
            }
        }
    }

    private void writeGenericValue(Element target, SoapJacksonValue node, //
            Map<Element, byte[]> binaries, int depth) throws Exception {
        this.requireDepth(depth);
        if (node.object()) {
            for (Map.Entry<String, SoapJacksonValue> field : node.fields().entrySet()) {
                String key = this.requireMapKey(target, field.getKey(), false);
                Element child = this.append(target, new QName(key));
                this.writeGenericChildValue(child, field.getValue(), binaries, depth + 1);
            }
        } else if (node.array()) {
            for (SoapJacksonValue item : node.items()) {
                Element child = this.append(target, new QName("item"));
                this.writeGenericChildValue(child, item, binaries, depth + 1);
            }
        } else if (node.value() == null) {
            this.writeNil(target);
        } else if (node.value() instanceof Element element) {
            this.writeAnyElement(target, element, depth);
        } else {
            target.setTextContent(this.scalarText(node.value()));
            if (node.value() instanceof byte[] bytes && binaries != null) {
                binaries.put(target, bytes.clone());
            }
        }
    }

    private void writeGenericChildValue(Element target, SoapJacksonValue value, //
            Map<Element, byte[]> binaries, int depth) throws Exception {
        if (value.object() && value.source() != null) {
            JavaType type = this.mapper.constructType(value.source().getClass());
            boolean bean = !SoapXmlMapping.isMap(type) && !SoapXmlMapping.isCollection(type);
            if (bean && !this.mapping.type(type).getProperties().isEmpty()) {
                // 自定义序列化器委托写出的普通 DTO 仍使用它自己的 SOAP 元素、属性与包装映射。
                this.writeBoundValue(target, type, value, binaries, depth, false);
                return;
            }
        }
        this.writeGenericValue(target, value, binaries, depth);
    }

    private void writeBoundAttribute(Element target, SoapXmlProperty property, SoapJacksonValue value, //
            SoapXmlType bean) {
        if (value.value() == null) {
            if (property.isRequired()) {
                throw this.missing(property);
            }
            return;
        }
        if (SoapXmlMapping.isMap(property.getJavaType())) {
            Map<String, SoapJacksonValue> values = value.fields();
            boolean missingValue = values.isEmpty() || values.values().stream().anyMatch(item -> item.value() == null);
            if (property.isRequired() && missingValue) {
                throw this.missing(property);
            }
            Set<QName> statics = this.staticAttributes(bean);
            for (Map.Entry<String, SoapJacksonValue> entry : values.entrySet()) {
                String key = this.requireMapKey(target, entry.getKey(), true);
                QName name = new QName(property.getName().getNamespaceURI(), key);
                if (statics.contains(name)) {
                    throw new IllegalArgumentException("SOAP XML Map key conflicts with a declared attribute: " + name);
                }
                if (entry.getValue().value() != null) {
                    this.writeBoundAttributeValue(target, name, entry.getValue());
                }
            }
            return;
        }
        this.writeBoundAttributeValue(target, property.getName(), value);
    }

    private void writeBoundAttributeValue(Element target, QName name, SoapJacksonValue value) {
        if (value.object() || value.array() || value.value() instanceof Element) {
            throw new IllegalArgumentException("SOAP XML attribute values must be scalar or enum values");
        }
        String prefix = this.prefix(target, name.getNamespaceURI());
        String qualified = StringUtils.isEmpty(prefix) ? name.getLocalPart() : prefix + ":" + name.getLocalPart();
        SoapXmlOutputUtils.attribute(target, name, qualified, this.scalarText(value.value()));
    }

    private void writeBoundProperty(Element target, SoapXmlProperty property, SoapJacksonValue value, //
            Map<Element, byte[]> binaries, int depth) throws Exception {
        JavaType type = property.getJavaType();
        Element scope = target;
        if (property.getWrapper() != null) {
            if (value.value() == null && !property.isWrapperNillable()) {
                if (property.isWrapperRequired()) {
                    throw new IllegalArgumentException("Required SOAP XML wrapper is null: " + property.getWrapper());
                }
                return;
            }
            scope = this.append(target, property.getWrapper());
            if (value.value() == null) {
                this.writeNil(scope);
                return;
            }
        }
        if (!SoapXmlMapping.isCollection(type) || property.isJacksonValue() && !property.isJacksonContent()) {
            if (value.value() == null && !property.isNillable()) {
                if (property.isRequired()) {
                    throw this.missing(property);
                }
                return;
            }
            if (SoapXmlMapping.isMap(type) && !property.isNillable() && value.object()) {
                for (SoapJacksonValue entry : value.fields().values()) {
                    if (entry.value() == null) {
                        throw new IllegalArgumentException("SOAP XML Map entry is not nillable: " + property.getName());
                    }
                }
            }
            Element child = this.append(scope, property.getName());
            if (SoapXmlMapping.isMap(type) && property.isJacksonContent() && value.object()) {
                this.writeBoundMapContent(child, property, value, binaries, depth + 1);
                return;
            }
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, type, property);
            boolean nativeShape = this.nativeWriteShape(property, value, discriminator);
            this.writeBoundValue(child, type, value, binaries, depth + 1, nativeShape, discriminator);
            return;
        }
        List<SoapJacksonValue> items = value.value() == null ? List.of() : value.items();
        if (items.isEmpty() && property.isRequired()) {
            throw this.missing(property);
        }
        for (SoapJacksonValue item : items) {
            if (item.value() == null && !property.isNillable()) {
                throw new IllegalArgumentException("SOAP XML collection item is not nillable: " + property.getName());
            }
            Element child = this.append(scope, property.getName());
            JavaType content = type.getContentType();
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, content, property);
            boolean nativeShape = property.isJacksonContent() && this.nativeWriteShape(property, item, discriminator);
            this.writeBoundValue(child, content, item, binaries, depth + 1, nativeShape, discriminator);
        }
    }

    private boolean nativeWriteShape(SoapXmlProperty property, SoapJacksonValue value, String discriminator) {
        if (!property.isJacksonValue() || discriminator != null) {
            return false;
        }
        if (value.source() != null) {
            JavaType actual = this.mapper.constructType(value.source().getClass());
            boolean structuredBean = !SoapXmlMapping.isMap(actual) && !SoapXmlMapping.isCollection(actual);
            if (structuredBean && !this.mapping.type(actual).getProperties().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void writeBoundMapContent(Element target, SoapXmlProperty property, SoapJacksonValue value, //
            Map<Element, byte[]> binaries, int depth) throws Exception {
        JavaType content = property.getJavaType().getContentType();
        if (value.source() instanceof Map<?, ?> original) {
            for (Object key : original.keySet()) {
                this.requireMapKey(target, key, false);
            }
        }
        for (Map.Entry<String, SoapJacksonValue> entry : value.fields().entrySet()) {
            String key = this.requireMapKey(target, entry.getKey(), false);
            Element child = this.append(target, new QName(this.namespace(target.getNamespaceURI()), key));
            String discriminator = SoapJacksonUtils.typeProperty(this.mapper, content, property);
            boolean nativeShape = this.nativeWriteShape(property, entry.getValue(), discriminator);
            this.writeBoundValue(child, content, entry.getValue(), binaries, depth + 1, nativeShape, discriminator);
        }
    }

    private String scalarText(Object value) {
        if (value instanceof Enum<?> enumeration) {
            return this.mapping.enumValue(enumeration);
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof char[] characters) {
            return new String(characters);
        }
        if (value instanceof Date date) {
            return Long.toString(date.getTime());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Float number) {
            if (number == Float.POSITIVE_INFINITY) {
                return "INF";
            }
            if (number == Float.NEGATIVE_INFINITY) {
                return "-INF";
            }
        }
        if (value instanceof Double number) {
            if (number == Double.POSITIVE_INFINITY) {
                return "INF";
            }
            if (number == Double.NEGATIVE_INFINITY) {
                return "-INF";
            }
        }
        return String.valueOf(value);
    }

    private Object scalarValue(String value, JavaType type) {
        Class<?> raw = type.getRawClass();
        if (raw == Object.class) {
            return value;
        }
        if (raw.isEnum()) {
            return this.mapping.enumValue(raw, value);
        }
        if (raw == byte[].class) {
            return Base64.getDecoder().decode(value.replaceAll("[ \t\r\n]", ""));
        }
        if (raw == char[].class) {
            return value.toCharArray();
        }
        if (raw == Date.class) {
            return new Date(Long.parseLong(value.trim()));
        }
        if (raw == char.class || raw == Character.class) {
            if (value.length() != 1) {
                throw new IllegalArgumentException("SOAP XML character must contain exactly one character");
            }
            return value.charAt(0);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            String normalized = value.trim();
            if (StringUtils.equals("true", normalized) || StringUtils.equals("1", normalized)) {
                return true;
            }
            if (StringUtils.equals("false", normalized) || StringUtils.equals("0", normalized)) {
                return false;
            }
            throw new IllegalArgumentException("Invalid XML Schema boolean: " + value);
        }
        String normalized = value;
        if (raw == Float.class || raw == float.class || raw == Double.class || raw == double.class) {
            if (StringUtils.equals("INF", value.trim())) {
                normalized = "Infinity";
            } else if (StringUtils.equals("-INF", value.trim())) {
                normalized = "-Infinity";
            }
        }
        return this.mapper.convertValue(normalized, type);
    }

    @SuppressWarnings("unchecked")
    private Object collection(JavaType type, List<Object> values) {
        if (type.isArrayType()) {
            Object array = Array.newInstance(type.getContentType().getRawClass(), values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(array, index, values.get(index));
            }
            return array;
        }
        Collection<Object> result = this.mapper.convertValue(Collections.emptyList(), type);
        result.addAll(values);
        return result;
    }

    private List<?> items(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        List<Object> result = new ArrayList<>();
        for (int index = 0; index < Array.getLength(value); index++) {
            result.add(Array.get(value, index));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> newMap(JavaType type) {
        return this.mapper.convertValue(Collections.emptyMap(), type);
    }

    private String requireMapKey(Element scope, Object value, boolean attribute) {
        String key = value instanceof String ? (String) value : null;
        boolean invalidName = StringUtils.isEmpty(key) || key.indexOf(':') >= 0;
        boolean reservedAttribute = attribute && StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE, key);
        if (invalidName || reservedAttribute) {
            throw new IllegalArgumentException("SOAP XML Map key must be a valid XML local name: " + value);
        }
        try {
            scope.getOwnerDocument().createElementNS(null, key);
        } catch (DOMException failure) {
            throw new IllegalArgumentException("SOAP XML Map key must be a valid XML local name: " + value, failure);
        }
        return key;
    }

    private void requireValueType(JavaType declared, Object value) {
        Class<?> assignable = ClassUtils.primitiveToWrapper(declared.getRawClass());
        if (!assignable.isInstance(value)) {
            throw new IllegalArgumentException("SOAP XML value does not match its declared type: " + declared);
        }
    }

    private Set<QName> staticAttributes(SoapXmlType bean) {
        Set<QName> result = new HashSet<>();
        for (SoapXmlProperty property : bean.getProperties()) {
            boolean staticAttribute = property.isAttribute() && !SoapXmlMapping.isMap(property.getJavaType());
            if (staticAttribute) {
                result.add(property.getName());
                for (String alias : property.getAliases()) {
                    result.add(new QName(property.getName().getNamespaceURI(), alias));
                }
            }
        }
        return result;
    }

    private QName attributeName(Attr attribute) {
        String local = attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
        return new QName(attribute.getNamespaceURI(), local);
    }

    private boolean controlAttribute(Attr attribute) {
        return CONTROL_ATTRIBUTE_NAMESPACES.contains(this.namespace(attribute.getNamespaceURI()));
    }

    private String namespace(String namespace) {
        return namespace == null ? "" : namespace;
    }

    private void requireAttributeNames(Element source, SoapXmlType type) {
        Set<QName> names = this.staticAttributes(type);
        Set<String> dynamicNamespaces = new HashSet<>();
        for (SoapXmlProperty property : type.getProperties()) {
            boolean dynamicAttribute = property.isAttribute() && SoapXmlMapping.isMap(property.getJavaType());
            if (dynamicAttribute) {
                dynamicNamespaces.add(property.getName().getNamespaceURI());
            }
        }
        NamedNodeMap attributes = source.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr actual = (Attr) attributes.item(index);
            if (this.controlAttribute(actual)) {
                continue;
            }
            QName name = this.attributeName(actual);
            if (dynamicNamespaces.contains(name.getNamespaceURI())) {
                continue;
            }
            boolean unexpectedNamespace = !dynamicNamespaces.contains(name.getNamespaceURI());
            if (!names.contains(name) && !dynamicNamespaces.isEmpty() && unexpectedNamespace) {
                throw new IllegalArgumentException("Unexpected SOAP XML attribute namespace: " + name);
            }
            this.requireKnownName(name, names, "attribute");
        }
    }

    private void requireKnownNames(Element source, SoapXmlType type) {
        Set<QName> elements = new HashSet<>();
        for (SoapXmlProperty property : type.getProperties()) {
            if (property.isAttribute()) {
                if (property.getWrapper() != null) {
                    elements.add(property.getWrapper());
                }
            } else if (!property.isText()) {
                QName name = property.getWrapper() == null ? property.getName() : property.getWrapper();
                elements.add(name);
            }
        }
        for (Element child : this.children(source)) {
            String localName = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            QName name = new QName(child.getNamespaceURI(), localName);
            this.requireKnownName(name, elements, "property");
        }
        this.requireAttributeNames(source, type);
    }

    private void requireKnownName(QName actual, Set<QName> names, String description) {
        if (names.contains(actual)) {
            return;
        }
        for (QName expected : names) {
            if (StringUtils.equals(expected.getLocalPart(), actual.getLocalPart())) {
                throw new IllegalArgumentException("Unexpected SOAP XML " + description + " namespace: " + actual);
            }
        }
    }

    private List<Element> children(Element source) {
        List<Element> result = new ArrayList<>();
        for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private boolean hasText(Element source) {
        for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
            boolean textNode = node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE;
            if (textNode && !node.getTextContent().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<Element> matching(Element source, QName name) {
        List<Element> result = new ArrayList<>();
        for (Element child : this.children(source)) {
            String localName = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (name.equals(new QName(child.getNamespaceURI(), localName))) {
                result.add(child);
            }
        }
        return result;
    }

    private Element single(Element source, QName name) {
        List<Element> matches = this.matching(source, name);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Duplicate SOAP XML property: " + name);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private Element append(Element parent, QName name) {
        String prefix = this.prefix(parent, name.getNamespaceURI());
        String qualified = StringUtils.isEmpty(prefix) ? name.getLocalPart() : prefix + ":" + name.getLocalPart();
        Element child = parent.getOwnerDocument().createElementNS(name.getNamespaceURI(), qualified);
        if (StringUtils.isEmpty(name.getNamespaceURI()) && parent.lookupNamespaceURI(null) != null) {
            child.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "");
        }
        parent.appendChild(child);
        return child;
    }

    private String prefix(Element scope, String namespace) {
        if (StringUtils.equals(XMLConstants.XML_NS_URI, namespace)) {
            return XMLConstants.XML_NS_PREFIX;
        }
        if (StringUtils.isEmpty(namespace)) {
            return "";
        }
        String existing = scope.lookupPrefix(namespace);
        if (existing != null) {
            return existing;
        }
        String prefix = "ns";
        int suffix = 1;
        while (scope.lookupNamespaceURI(prefix) != null) {
            prefix = "ns" + suffix++;
        }
        scope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace);
        return prefix;
    }

    private void writeNil(Element target) {
        target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NAMESPACE);
        target.setAttributeNS(XSI_NAMESPACE, "xsi:nil", "true");
    }

    private boolean nil(Element element) {
        if (!element.hasAttributeNS(XSI_NAMESPACE, "nil")) {
            return false;
        }
        String value = element.getAttributeNS(XSI_NAMESPACE, "nil").trim();
        if (StringUtils.equals("true", value) || StringUtils.equals("1", value)) {
            return true;
        }
        if (StringUtils.equals("false", value) || StringUtils.equals("0", value)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid SOAP XML xsi:nil value: " + value);
    }

    private void requireNillable(Element value, boolean nillable, QName name) {
        if (!nillable && this.nil(value)) {
            throw new IllegalArgumentException("SOAP XML property is not nillable: " + name);
        }
    }

    private IllegalArgumentException missing(SoapXmlProperty property) {
        return new IllegalArgumentException("Required SOAP XML property has no value: " + property.getName());
    }

    private void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("SOAP XML value exceeds nesting depth " + MAX_DEPTH);
        }
    }
}
