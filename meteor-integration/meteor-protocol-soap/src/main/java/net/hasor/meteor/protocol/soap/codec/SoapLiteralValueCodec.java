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
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlType;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** 按共享 XML 映射读写 literal 值，QName 与 Java 属性名称分别匹配。 */
final class SoapLiteralValueCodec {
    private static final String         XSI_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final int            MAX_DEPTH     = 128;
    private final        XmlMapper      mapper;
    private final        SoapXmlMapping mapping;

    SoapLiteralValueCodec(XmlMapper mapper) {
        this.mapper = mapper;
        this.mapping = new SoapXmlMapping(mapper);
    }

    Object read(Element element, Type type) throws Exception {
        return this.readValue(element, this.mapper.constructType(type), 0);
    }

    void write(Element target, Type type, Object value, Map<Element, byte[]> binaries) throws Exception {
        this.writeValue(target, this.mapper.constructType(type), value, binaries, new IdentityHashMap<>(), 0);
    }

    private Object readValue(Element element, JavaType type, int depth) throws Exception {
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

        if (SoapXmlMapping.isScalar(type) || type.isEnumType()) {
            if (!this.children(element).isEmpty()) {
                throw new IllegalArgumentException("SOAP XML scalar must contain only text: " + type);
            }
            return this.scalarValue(element.getTextContent(), type);
        }
        if (SoapXmlMapping.isCollection(type)) {
            List<Object> values = new ArrayList<>();
            for (Element item : this.children(element)) {
                values.add(this.readValue(item, type.getContentType(), depth + 1));
            }
            return this.collection(type, values);
        }

        // 每个属性按完整 QName 取值，来自其他命名空间的同名元素不会覆盖它。
        SoapXmlType bean = this.mapping.type(type);
        Object result = bean.newInstance();
        this.requireKnownNames(element, bean);
        for (SoapXmlProperty property : bean.getProperties()) {
            if (property.isAttribute()) {
                this.readAttribute(element, property, result);
            } else if (property.isText()) {
                if (!this.children(element).isEmpty()) {
                    throw new IllegalArgumentException("SOAP XML text value cannot contain child elements");
                }
                property.write(result, this.scalarValue(element.getTextContent(), property.getJavaType()));
            } else {
                this.readProperty(element, property, result, depth);
            }
        }
        return result;
    }

    private void readAttribute(Element source, SoapXmlProperty property, Object result) {
        QName name = property.getName();
        String namespace = StringUtils.isEmpty(name.getNamespaceURI()) ? null : name.getNamespaceURI();
        Attr attribute = source.getAttributeNodeNS(namespace, name.getLocalPart());
        if (attribute != null) {
            property.write(result, this.scalarValue(attribute.getValue(), property.getJavaType()));
        } else if (property.isRequired()) {
            throw this.missing(property);
        }
    }

    private void readProperty(Element source, SoapXmlProperty property, Object result, int depth) throws Exception {
        JavaType type = property.getJavaType();
        if (!SoapXmlMapping.isCollection(type)) {
            Element value = this.single(source, property.getName());
            if (value == null) {
                if (property.isRequired()) {
                    throw this.missing(property);
                }
                return;
            }
            this.requireNillable(value, property.isNillable(), property.getName());
            property.write(result, this.readValue(value, type, depth + 1));
            return;
        }

        // 集合包装单独区分缺省、nil、空集合及包含一个 nil 成员的集合。
        Element scope = source;
        if (property.getWrapper() != null) {
            scope = this.single(source, property.getWrapper());
            if (scope == null) {
                if (property.isWrapperRequired()) {
                    throw new IllegalArgumentException("Required SOAP XML wrapper is absent: " + property.getWrapper());
                }
                return;
            }
            this.requireNillable(scope, property.isWrapperNillable(), property.getWrapper());
            if (this.nil(scope)) {
                this.readValue(scope, type, depth + 1);
                property.write(result, null);
                return;
            }
        }
        List<Element> items = this.matching(scope, property.getName());
        if (property.getWrapper() != null && items.size() != this.children(scope).size()) {
            throw new IllegalArgumentException("Unexpected SOAP XML collection item in " + property.getWrapper());
        }
        if (items.isEmpty() && property.isRequired()) {
            throw this.missing(property);
        }
        if (items.isEmpty() && property.getWrapper() == null) {
            return;
        }
        List<Object> values = new ArrayList<>();
        for (Element item : items) {
            this.requireNillable(item, property.isNillable(), property.getName());
            values.add(this.readValue(item, type.getContentType(), depth + 1));
        }
        property.write(result, this.collection(type, values));
    }

    private void writeValue(Element target, JavaType type, Object value, //
            Map<Element, byte[]> binaries, IdentityHashMap<Object, Boolean> active, int depth) throws Exception {
        this.requireDepth(depth);
        if (value == null) {
            this.writeNil(target);
            return;
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

            // 属性只读取一次，真实二进制值直接登记到最终文档中的元素位置。
            SoapXmlType bean = this.mapping.type(type);
            for (SoapXmlProperty property : bean.getProperties()) {
                Object propertyValue = property.read(value);
                if (property.isAttribute()) {
                    this.writeAttribute(target, property, propertyValue);
                } else if (property.isText()) {
                    if (propertyValue != null) {
                        this.writeValue(target, property.getJavaType(), propertyValue, binaries, active, depth + 1);
                    }
                } else {
                    this.writeProperty(target, property, propertyValue, binaries, active, depth);
                }
            }
        } finally {
            active.remove(value);
        }
    }

    private void writeAttribute(Element target, SoapXmlProperty property, Object value) {
        if (value == null) {
            if (property.isRequired()) {
                throw this.missing(property);
            }
            return;
        }
        QName name = property.getName();
        String prefix = this.prefix(target, name.getNamespaceURI());
        String qualified = StringUtils.isEmpty(prefix) ? name.getLocalPart() : prefix + ":" + name.getLocalPart();
        target.setAttributeNS(name.getNamespaceURI(), qualified, this.scalarText(value));
    }

    private void writeProperty(Element target, SoapXmlProperty property, Object value, //
            Map<Element, byte[]> binaries, IdentityHashMap<Object, Boolean> active, int depth) throws Exception {
        JavaType type = property.getJavaType();
        if (!SoapXmlMapping.isCollection(type)) {
            if (value == null && !property.isNillable()) {
                if (property.isRequired()) {
                    throw this.missing(property);
                }
                return;
            }
            Element child = this.append(target, property.getName());
            this.writeValue(child, type, value, binaries, active, depth + 1);
            return;
        }

        Element scope = target;
        if (property.getWrapper() != null) {
            if (value == null && !property.isWrapperNillable()) {
                if (property.isWrapperRequired()) {
                    throw new IllegalArgumentException("Required SOAP XML wrapper is null: " + property.getWrapper());
                }
                return;
            }
            scope = this.append(target, property.getWrapper());
            if (value == null) {
                this.writeNil(scope);
                return;
            }
        }
        List<?> items = value == null ? Collections.emptyList() : this.items(value);
        if (items.isEmpty() && property.isRequired()) {
            throw this.missing(property);
        }
        for (Object item : items) {
            if (item == null && !property.isNillable()) {
                throw new IllegalArgumentException("SOAP XML collection item is not nillable: " + property.getName());
            }
            Element child = this.append(scope, property.getName());
            this.writeValue(child, type.getContentType(), item, binaries, active, depth + 1);
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
        return this.mapper.convertValue(value, String.class);
    }

    private Object scalarValue(String value, JavaType type) {
        Class<?> raw = type.getRawClass();
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

    private void requireKnownNames(Element source, SoapXmlType type) {
        Set<QName> elements = new HashSet<>();
        Set<QName> attributes = new HashSet<>();
        for (SoapXmlProperty property : type.getProperties()) {
            if (property.isAttribute()) {
                attributes.add(property.getName());
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
        NamedNodeMap sourceAttributes = source.getAttributes();
        for (int index = 0; index < sourceAttributes.getLength(); index++) {
            Attr actual = (Attr) sourceAttributes.item(index);
            String namespace = actual.getNamespaceURI();
            boolean schemaAttribute = StringUtils.equals(XSI_NAMESPACE, namespace);
            boolean declaration = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace);
            boolean control = schemaAttribute || declaration;
            if (!control) {
                String local = actual.getLocalName() == null ? actual.getName() : actual.getLocalName();
                this.requireKnownName(new QName(namespace, local), attributes, "attribute");
            }
        }
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
