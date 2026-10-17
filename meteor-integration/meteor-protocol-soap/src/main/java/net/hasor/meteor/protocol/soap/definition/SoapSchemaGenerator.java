/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 共用运行期 XML 属性模型，生成完整 QName、属性和集合包装的 XML Schema。 */
final class SoapSchemaGenerator {
    private static final String                XSD_NAMESPACE = SoapWsdlParser.XSD_NAMESPACE;
    private final        XmlMapper             mapper        = SoapXmlMapping.newMapper();
    private final        SoapXmlMapping        mapping       = new SoapXmlMapping(this.mapper);
    private final        String                namespace;
    private final        SoapVersion           encodingVersion;
    private final        Map<String, Document> schemas       = new LinkedHashMap<>();
    private final        Map<String, String>   locations     = new LinkedHashMap<>();
    private final        Map<JavaType, QName>  types         = new LinkedHashMap<>();
    private final        Map<QName, JavaType>  typeNames     = new LinkedHashMap<>();
    private final        Map<QName, String>    elements      = new LinkedHashMap<>();
    private final        Map<QName, QName>     attributes    = new LinkedHashMap<>();

    SoapSchemaGenerator(String namespace, SoapVersion encodingVersion) {
        this.namespace = namespace;
        this.encodingVersion = encodingVersion;
        this.schema(namespace);
    }

    Document document() {
        return this.schemas.get(this.namespace);
    }

    Map<String, Document> documents() {
        Map<String, Document> documents = new LinkedHashMap<>();
        for (Map.Entry<String, Document> schema : this.schemas.entrySet()) {
            documents.put(this.locations.get(schema.getKey()), schema.getValue());
        }
        return documents;
    }

    void operation(Method method, boolean oneWay) {
        // 未标注的方法继续使用稳定的 argN/return 名称，不依赖编译器保存形参名。
        Element input = this.wrapper(method.getName());
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            JavaType type = this.mapper.constructType(parameter.getParameterizedType());
            this.operationValue(input, "arg" + index, type, parameter.getAnnotation(MetXmlElement.class));
        }
        if (!oneWay) {
            Element output = this.wrapper(method.getName() + "Response");
            if (method.getReturnType() != void.class) {
                JavaType type = this.mapper.constructType(method.getGenericReturnType());
                this.operationValue(output, "return", type, method.getAnnotation(MetXmlElement.class));
            }
        }
    }

    private void operationValue(Element parent, String fallback, JavaType type, MetXmlElement annotation) {
        String name = annotation == null || annotation.name().isEmpty() ? fallback : annotation.name();
        String namespace = annotation == null ? "" : annotation.namespace();
        boolean xml = StringUtils.equals(XMLConstants.XML_NS_URI, namespace);
        boolean xmlns = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace);
        if (xml || xmlns) {
            String reason = "SOAP operation element cannot use a reserved XML namespace: " + namespace;
            throw new IllegalArgumentException(reason);
        }
        boolean required = type.isPrimitive() || annotation == null || annotation.required();
        boolean nillable = !type.isPrimitive() && (annotation == null || annotation.nillable());
        this.value(parent, new QName(namespace, name), type, required, nillable, false);
    }

    private Element wrapper(String name) {
        QName wrapperName = new QName(this.namespace, name);
        if (this.elements.putIfAbsent(wrapperName, "operation wrapper") != null) {
            throw new IllegalArgumentException("SOAP operation wrappers have the same name: " + name);
        }
        Element wrapper = this.append(this.schema(this.namespace), "element");
        wrapper.setAttribute("name", name);
        return this.append(this.append(wrapper, "complexType"), "sequence");
    }

    QName elementName(Element element) {
        String reference = element.getAttribute("ref");
        if (!reference.isEmpty()) {
            return this.resolve(element, reference);
        }
        String namespace = "";
        if (StringUtils.equals("qualified", element.getAttribute("form"))) {
            namespace = this.ownerNamespace(element);
        }
        return new QName(namespace, element.getAttribute("name"));
    }

    QName elementType(Element element) {
        String reference = element.getAttribute("ref");
        if (!reference.isEmpty()) {
            element = this.globalElement(this.resolve(element, reference));
        }
        return this.resolve(element, element.getAttribute("type"));
    }

    void promote(Element element, QName name) {
        QName type = this.elementType(element);
        String reference = element.getAttribute("ref");
        Element declaration = reference.isEmpty() ? element : this.globalElement(this.resolve(element, reference));
        boolean nillable = StringUtils.equals("true", declaration.getAttribute("nillable"));
        this.globalValue(name, type, nillable);
        if (!StringUtils.equals(this.namespace, name.getNamespaceURI())) {
            this.importNamespace(this.document().getDocumentElement(), name.getNamespaceURI());
        }
    }

    private Element value(Element parent, QName name, JavaType type, boolean required, //
            boolean nillable, boolean repeated) {
        QName xmlType = this.typeName(type);
        Element element = this.localElement(parent, name, xmlType, nillable && !type.isPrimitive());
        if (!required) {
            element.setAttribute("minOccurs", "0");
        }
        if (repeated) {
            element.setAttribute("maxOccurs", "unbounded");
        }
        return element;
    }

    private Element localElement(Element parent, QName name, QName type, boolean nillable) {
        SoapXmlUtils.requireName(parent.getOwnerDocument(), name.getLocalPart(), "SOAP property");
        Element element = this.append(parent, "element");
        String owner = this.ownerNamespace(parent);
        if (name.getNamespaceURI().isEmpty() || StringUtils.equals(owner, name.getNamespaceURI())) {
            element.setAttribute("name", name.getLocalPart());
            if (!name.getNamespaceURI().isEmpty()) {
                element.setAttribute("form", "qualified");
            }
            element.setAttribute("type", this.reference(element, type));
            if (nillable) {
                element.setAttribute("nillable", "true");
            }
        } else {
            this.globalValue(name, type, nillable);
            element.setAttribute("ref", this.reference(element, name));
        }
        return element;
    }

    private void globalValue(QName name, QName type, boolean nillable) {
        String contract = type + ":" + nillable;
        String previous = this.elements.putIfAbsent(name, contract);
        if (previous != null) {
            if (!StringUtils.equals(previous, contract)) {
                throw new IllegalArgumentException("Conflicting SOAP global XML element: " + name);
            }
            return;
        }
        Element element = this.append(this.schema(name.getNamespaceURI()), "element");
        element.setAttribute("name", name.getLocalPart());
        element.setAttribute("type", this.reference(element, type));
        if (nillable) {
            element.setAttribute("nillable", "true");
        }
    }

    private QName typeName(JavaType type) {
        String scalar = this.scalar(type.getRawClass());
        if (scalar != null) {
            return new QName(XSD_NAMESPACE, scalar);
        }
        QName existing = this.types.get(type);
        if (existing != null) {
            return existing;
        }
        if (this.types.size() >= 256) {
            throw this.unsupported(type, "more than 256 distinct XML types");
        }
        if (type.hasRawClass(Object.class) || type.isMapLikeType() || type.isReferenceType()) {
            throw this.unsupported(type, "a concrete XML value type is required");
        }
        SoapXmlType mapping = this.mapping.type(type);
        String name = mapping.getName().isEmpty() ? this.generatedTypeName() : mapping.getName();
        String namespace = mapping.getNamespace().isEmpty() ? this.namespace : mapping.getNamespace();
        QName typeName = new QName(namespace, name);
        SoapXmlUtils.requireName(this.document(), name, "SOAP XML type");
        JavaType previous = this.typeNames.putIfAbsent(typeName, type);
        if (previous != null && !previous.equals(type)) {
            String reason = "XML type QName is already used by " + previous.toCanonical() + ": " + typeName;
            throw this.unsupported(type, reason);
        }

        // 先登记，再生成属性，允许普通 POJO 通过类型引用表达递归。
        this.types.put(type, typeName);
        if (type.isEnumType()) {
            this.enumeration(type, typeName);
        } else if (SoapXmlMapping.isCollection(type)) {
            this.array(type, typeName);
        } else {
            this.bean(mapping, typeName);
        }
        return typeName;
    }

    private String generatedTypeName() {
        int number = this.types.size() + 1;
        while (this.typeNames.containsKey(new QName(this.namespace, "Type" + number))) {
            number++;
        }
        return "Type" + number;
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

    private JavaType contentType(JavaType type) {
        JavaType content = type.getContentType();
        if (content == null || content.hasRawClass(Object.class)) {
            throw this.unsupported(type, "collection elements require a concrete Java type");
        }
        boolean nested = SoapXmlMapping.isCollection(content) && this.scalar(content.getRawClass()) == null;
        if (this.encodingVersion == null && nested) {
            throw this.unsupported(type, "nested collections do not retain their structure in XML");
        }
        return content;
    }

    private void array(JavaType type, QName name) {
        Element complex = this.namedType(name, "complexType");
        JavaType content = this.contentType(type);
        if (this.encodingVersion == null) {
            this.value(this.append(complex, "sequence"), new QName("", "item"), content, false, true, true);
            return;
        }

        // SOAP encoded 的数组保持标准 array/id/ref 描述，不套用 literal 的集合包装。
        int dimensions = 1;
        while (type.isArrayType() && content.isArrayType() && this.scalar(content.getRawClass()) == null) {
            dimensions++;
            content = this.contentType(content);
        }
        Element container = complex;
        if (this.encodingVersion == SoapVersion.SOAP_11) {
            container = this.append(this.append(complex, "complexContent"), "restriction");
            container.setAttribute("base", this.reference(container, new QName(this.encodingNamespace(), "Array")));
        }
        this.value(this.append(container, "sequence"), new QName("", "item"), content, false, true, true);
        if (this.encodingVersion == SoapVersion.SOAP_11) {
            Element attribute = this.append(container, "attribute");
            attribute.setAttribute("ref", this.reference(attribute, new QName(this.encodingNamespace(), "arrayType")));
            String rank = "[" + ",".repeat(dimensions - 1) + "]";
            String arrayType = this.reference(attribute, this.typeName(content)) + rank;
            attribute.setAttributeNS(SoapWsdlParser.WSDL_NAMESPACE, "wsdl:arrayType", arrayType);
        } else {
            Element arrays = this.append(container, "attributeGroup");
            arrays.setAttribute("ref", this.reference(arrays, new QName(this.encodingNamespace(), "arrayAttributes")));
            Element references = this.append(container, "attributeGroup");
            QName commonAttributes = new QName(this.encodingNamespace(), "commonAttributes");
            references.setAttribute("ref", this.reference(references, commonAttributes));
        }
    }

    private void enumeration(JavaType type, QName name) {
        Element restriction = this.append(this.namedType(name, "simpleType"), "restriction");
        restriction.setAttribute("base", "xsd:string");
        Set<String> values = new HashSet<>();
        for (Object constant : type.getRawClass().getEnumConstants()) {
            String value = this.mapping.enumValue((Enum<?>) constant);
            if (!values.add(value)) {
                throw this.unsupported(type, "duplicate XML enumeration value: " + value);
            }
            this.append(restriction, "enumeration").setAttribute("value", value);
        }
    }

    private void bean(SoapXmlType mapping, QName name) {
        Element complex = this.namedType(name, "complexType");
        JavaType javaType = mapping.getJavaType();
        SoapXmlProperty text = null;
        for (SoapXmlProperty property : mapping.getProperties()) {
            if (this.encodingVersion != null && property.getWrapper() != null) {
                throw this.unsupported(javaType, "SOAP encoded values do not support XML collection wrappers");
            }
            if (property.isText()) {
                if (this.encodingVersion != null) {
                    throw this.unsupported(javaType, "SOAP encoded values do not support XML text properties");
                }
                text = property;
            }
        }
        Element content;
        if (text != null) {
            content = this.append(this.append(complex, "simpleContent"), "extension");
            content.setAttribute("base", this.reference(content, this.typeName(text.getJavaType())));
        } else {
            content = complex;
            Element sequence = this.append(content, "sequence");
            for (SoapXmlProperty property : mapping.getProperties()) {
                if (!property.isAttribute()) {
                    this.property(sequence, property);
                }
            }
        }
        for (SoapXmlProperty property : mapping.getProperties()) {
            if (property.isAttribute()) {
                this.requireEncodedAttribute(mapping.getJavaType(), property.getName());
                this.attribute(content, property);
            }
        }
    }

    private void property(Element sequence, SoapXmlProperty property) {
        JavaType type = property.getJavaType();
        boolean collection = SoapXmlMapping.isCollection(type) && this.scalar(type.getRawClass()) == null;
        boolean repeated = this.encodingVersion == null && collection;
        if (property.getWrapper() != null) {
            JavaType contentType = this.contentType(type);
            this.collectionWrapper(sequence, property, contentType);
        } else {
            JavaType valueType = repeated ? this.contentType(type) : type;
            this.value(sequence, property.getName(), valueType, property.isRequired(), property.isNillable(), repeated);
        }
    }

    private void collectionWrapper(Element sequence, SoapXmlProperty property, JavaType contentType) {
        QName wrapperName = property.getWrapper();
        String owner = this.ownerNamespace(sequence);
        String wrapperNamespace = wrapperName.getNamespaceURI();
        boolean foreign = !wrapperNamespace.isEmpty() && !StringUtils.equals(owner, wrapperNamespace);
        Element wrapper;
        if (foreign) {
            QName itemType = this.typeName(contentType);
            String occurrence = property.isNillable() + ":" + property.isRequired();
            String itemContract = property.getName() + ":" + itemType + ":" + occurrence;
            String contract = "wrapper:" + property.isWrapperNillable() + ":" + itemContract;
            String previous = this.elements.putIfAbsent(wrapperName, contract);
            if (previous != null && !StringUtils.equals(previous, contract)) {
                throw new IllegalArgumentException("Conflicting SOAP global XML element: " + wrapperName);
            }
            if (previous == null) {
                Element global = this.append(this.schema(wrapperName.getNamespaceURI()), "element");
                global.setAttribute("name", wrapperName.getLocalPart());
                this.wrapperContent(global, property, contentType);
            }
            wrapper = this.append(sequence, "element");
            wrapper.setAttribute("ref", this.reference(wrapper, wrapperName));
        } else {
            wrapper = this.append(sequence, "element");
            wrapper.setAttribute("name", wrapperName.getLocalPart());
            if (!wrapperName.getNamespaceURI().isEmpty()) {
                wrapper.setAttribute("form", "qualified");
            }
            this.wrapperContent(wrapper, property, contentType);
        }
        if (!property.isWrapperRequired()) {
            wrapper.setAttribute("minOccurs", "0");
        }
    }

    private void wrapperContent(Element wrapper, SoapXmlProperty property, JavaType contentType) {
        if (property.isWrapperNillable()) {
            wrapper.setAttribute("nillable", "true");
        }
        Element sequence = this.append(this.append(wrapper, "complexType"), "sequence");
        this.value(sequence, property.getName(), contentType, property.isRequired(), property.isNillable(), true);
    }

    private void requireEncodedAttribute(JavaType type, QName name) {
        if (this.encodingVersion != null && this.encodingVersion.isEncodingControlAttribute(name)) {
            throw this.unsupported(type, "XML attribute conflicts with SOAP encoded control attribute: " + name);
        }
    }

    private void attribute(Element parent, SoapXmlProperty property) {
        QName name = property.getName();
        QName type = this.typeName(property.getJavaType());
        Element attribute = this.append(parent, "attribute");
        String owner = this.ownerNamespace(parent);
        if (StringUtils.equals(XMLConstants.XML_NS_URI, name.getNamespaceURI())) {
            Set<String> standard = Set.of("lang", "space", "base", "id");
            if (!standard.contains(name.getLocalPart()) || !property.getJavaType().hasRawClass(String.class)) {
                String reason = "standard xml attributes require a String lang, space, base or id property";
                throw this.unsupported(property.getJavaType(), reason);
            }
            attribute.setAttribute("ref", this.reference(attribute, name));
        } else if (name.getNamespaceURI().isEmpty() || StringUtils.equals(owner, name.getNamespaceURI())) {
            attribute.setAttribute("name", name.getLocalPart());
            attribute.setAttribute("type", this.reference(attribute, type));
            if (!name.getNamespaceURI().isEmpty()) {
                attribute.setAttribute("form", "qualified");
            }
        } else {
            QName previous = this.attributes.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("Conflicting SOAP global XML attribute: " + name);
            }
            if (previous == null) {
                Element global = this.append(this.schema(name.getNamespaceURI()), "attribute");
                global.setAttribute("name", name.getLocalPart());
                global.setAttribute("type", this.reference(global, type));
            }
            attribute.setAttribute("ref", this.reference(attribute, name));
        }
        if (property.isRequired()) {
            attribute.setAttribute("use", "required");
        }
    }

    private Element namedType(QName name, String kind) {
        Element type = this.append(this.schema(name.getNamespaceURI()), kind);
        type.setAttribute("name", name.getLocalPart());
        return type;
    }

    private Element schema(String namespace) {
        Document existing = this.schemas.get(namespace);
        if (existing != null) {
            return existing.getDocumentElement();
        }
        Document document = SoapXmlUtils.newDocument();
        Element schema = document.createElementNS(XSD_NAMESPACE, "xsd:schema");
        document.appendChild(schema);
        schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD_NAMESPACE);
        if (!namespace.isEmpty()) {
            schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:tns", namespace);
            schema.setAttribute("targetNamespace", namespace);
        }
        schema.setAttribute("elementFormDefault", "unqualified");
        String location = this.schemas.isEmpty() ? "types.xsd" : "types-" + this.schemas.size() + ".xsd";
        this.schemas.put(namespace, document);
        this.locations.put(namespace, location);
        if (this.encodingVersion != null) {
            schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:soapenc", this.encodingNamespace());
            schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsdl", SoapWsdlParser.WSDL_NAMESPACE);
            this.importNamespace(schema, this.encodingNamespace());
        }
        return schema;
    }

    private String reference(Element owner, QName name) {
        Element schema = owner.getOwnerDocument().getDocumentElement();
        String namespace = name.getNamespaceURI();
        if (namespace.isEmpty()) {
            return name.getLocalPart();
        }
        String prefix = StringUtils.equals(XMLConstants.XML_NS_URI, namespace) ? "xml" : schema.lookupPrefix(namespace);
        if (prefix == null) {
            prefix = "ns" + schema.getAttributes().getLength();
            schema.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace);
        }
        String currentNamespace = this.ownerNamespace(owner);
        if (!StringUtils.equals(namespace, XSD_NAMESPACE) && !StringUtils.equals(namespace, currentNamespace)) {
            this.importNamespace(schema, namespace);
        }
        return prefix + ":" + name.getLocalPart();
    }

    private void importNamespace(Element schema, String namespace) {
        for (Node node = schema.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element imported)) {
                continue;
            }
            boolean sameNamespace = StringUtils.equals(namespace, imported.getAttribute("namespace"));
            if (sameNamespace && StringUtils.equals("import", imported.getLocalName())) {
                return;
            }
        }
        Element imported = schema.getOwnerDocument().createElementNS(XSD_NAMESPACE, "xsd:import");
        if (!namespace.isEmpty()) {
            imported.setAttribute("namespace", namespace);
        }
        if (this.locations.containsKey(namespace)) {
            imported.setAttribute("schemaLocation", this.locations.get(namespace));
        } else if (StringUtils.equals(XMLConstants.XML_NS_URI, namespace)) {
            imported.setAttribute("schemaLocation", "xml-namespace.xsd");
        } else if (this.encodingVersion != null && StringUtils.equals(this.encodingNamespace(), namespace)) {
            String location = "soap-encoding-" + this.encodingVersion.displayName() + ".xsd";
            imported.setAttribute("schemaLocation", location);
        }
        schema.insertBefore(imported, schema.getFirstChild());
    }

    private String encodingNamespace() {
        if (this.encodingVersion == SoapVersion.SOAP_11) {
            return "http://schemas.xmlsoap.org/soap/encoding/";
        }
        return "http://www.w3.org/2003/05/soap-encoding";
    }

    private String ownerNamespace(Element element) {
        return element.getOwnerDocument().getDocumentElement().getAttribute("targetNamespace");
    }

    private QName resolve(Element owner, String value) {
        int separator = value.indexOf(':');
        String prefix = separator < 0 ? null : value.substring(0, separator);
        String local = separator < 0 ? value : value.substring(separator + 1);
        String namespace = owner.lookupNamespaceURI(prefix);
        return new QName(namespace == null ? "" : namespace, local);
    }

    private Element globalElement(QName name) {
        Element schema = this.schemas.get(name.getNamespaceURI()).getDocumentElement();
        for (Node node = schema.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            boolean sameName = StringUtils.equals(name.getLocalPart(), element.getAttribute("name"));
            if (sameName && StringUtils.equals("element", element.getLocalName())) {
                return element;
            }
        }
        throw new IllegalStateException("Generated SOAP schema is missing element: " + name);
    }

    private Element append(Element parent, String localName) {
        return SoapXmlUtils.append(parent, XSD_NAMESPACE, "xsd:" + localName);
    }

    private IllegalArgumentException unsupported(JavaType type, String reason) {
        return new IllegalArgumentException("Cannot generate SOAP schema for " + type.toCanonical() + ": " + reason);
    }
}
