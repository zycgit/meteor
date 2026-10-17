/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.*;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

/** 解析 SOAP 契约引用的 XML Schema 组件和包装体粒子。 */
final class SoapSchemaParser {
    private static final String      SOAP_SCHEMA_ROOT        = "/META-INF/meteor/protocol/soap/";
    private static final String      SOAP_11_SCHEMA          = "soap-encoding-1.1.xsd";
    private static final String      SOAP_12_SCHEMA          = "soap-encoding-1.2.xsd";
    private static final String      SOAP_11_ENVELOPE_SCHEMA = "soap-envelope-1.1.xsd";
    private static final String      SOAP_12_ENVELOPE_SCHEMA = "soap-envelope-1.2.xsd";
    private static final String      XML_NAMESPACE_SCHEMA    = "xml-namespace.xsd";
    private static final String      SOAP_11_ENCODING        = "http://schemas.xmlsoap.org/soap/encoding/";
    private static final String      SOAP_12_ENCODING        = "http://www.w3.org/2003/05/soap-encoding";
    private static final String      SOAP_11_ENVELOPE        = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String      SOAP_12_ENVELOPE        = "http://www.w3.org/2003/05/soap-envelope";
    private static final String      XML_NAMESPACE           = XMLConstants.XML_NS_URI;
    private static final Set<String> XSD_TYPES               = Set.of( //
            "anyType", "anySimpleType", "string", "boolean", "decimal", "float", "double", "duration", //
            "dateTime", "time", "date", "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth", //
            "hexBinary", "base64Binary", "anyURI", "QName", "NOTATION", "normalizedString", "token", //
            "language", "Name", "NCName", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "NMTOKEN", //
            "NMTOKENS", "integer", "nonPositiveInteger", "negativeInteger", "long", "int", "short", //
            "byte", "nonNegativeInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte", //
            "positiveInteger");
    private static final QName       ANY_TYPE                = new QName(SoapWsdlParser.XSD_NAMESPACE, "anyType");

    private final Map<String, SoapXmlResource>  resources;
    private final Map<QName, SchemaComponent>   elements;
    private final Map<QName, SchemaComponent>   types;
    private final Map<QName, SchemaComponent>   attributes;
    private final Map<QName, SchemaComponent>   attributeGroups;
    private final Map<QName, WrapperDefinition> wrappers;
    private final Map<Element, Set<String>>     indexedViews;
    private final Schema                        validationSchema;

    SoapSchemaParser(Collection<SoapXmlResource> resources, SoapXmlResource entry) throws IOException {
        if (resources == null || entry == null) {
            throw new IllegalArgumentException("Schema resources and entry are required.");
        }

        this.resources = this.indexResources(resources);
        this.elements = new LinkedHashMap<>();
        this.types = new LinkedHashMap<>();
        this.attributes = new LinkedHashMap<>();
        this.attributeGroups = new LinkedHashMap<>();
        this.wrappers = new LinkedHashMap<>();
        this.indexedViews = new IdentityHashMap<>();

        // 入口资源优先索引，使重复组件错误保持稳定。
        SoapXmlResource indexedEntry = this.resources.getOrDefault(entry.name(), entry);
        this.indexResource(indexedEntry);
        for (SoapXmlResource resource : this.resources.values()) {
            if (resource != indexedEntry) {
                this.indexResource(resource);
            }
        }
        this.validationSchema = this.compileSchema(indexedEntry);
    }

    static SoapSchemaParser generated(Map<String, Document> schemas, Map<String, byte[]> bodies) throws IOException {
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("Generated XML Schemas are required.");
        }
        List<SoapXmlResource> resources = new ArrayList<>();
        for (Map.Entry<String, Document> schema : schemas.entrySet()) {
            String name = schema.getKey();
            URL source = URI.create("file:/meteor-generated/" + name).toURL();
            resources.add(new SoapXmlResource(name, source, bodies.get(name), schema.getValue()));
        }
        return new SoapSchemaParser(resources, resources.get(0));
    }

    boolean hasElement(QName elementName) {
        return this.elements.containsKey(elementName);
    }

    boolean hasType(QName typeName) {
        if (typeName == null) {
            return false;
        }
        if (StringUtils.equals(SoapWsdlParser.XSD_NAMESPACE, typeName.getNamespaceURI())) {
            return XSD_TYPES.contains(typeName.getLocalPart());
        }
        return this.types.containsKey(typeName);
    }

    QName elementType(QName elementName) {
        SchemaComponent component = this.elements.get(elementName);
        return component == null ? null : component.type();
    }

    List<SoapPart> wrapperParts(QName wrapper) throws IOException {
        return this.wrapper(wrapper).parts();
    }

    List<SoapChoice> wrapperChoices(QName wrapper) throws IOException {
        return this.wrapper(wrapper).choices();
    }

    void validateBody(Element body, SoapBody binding) throws IOException {
        if (body == null || binding == null) {
            throw new IllegalArgumentException("SOAP Body and binding are required.");
        }
        if (binding.encoded()) {
            return;
        }

        QName wrapper = binding.wrapper();
        if (wrapper != null) {
            if (!this.hasElement(wrapper)) {
                return;
            }
            Element payload = this.child(body, wrapper);
            if (payload == null) {
                throw new IOException("SOAP Body does not contain its declared wrapper: " + wrapper);
            }
            this.validate(payload);
            return;
        }

        // document/literal bare 的每个 part 都是独立的全局 Schema 元素。
        Set<QName> globalParts = new LinkedHashSet<>();
        for (SoapPart part : binding.parts()) {
            if (this.hasElement(part.element())) {
                globalParts.add(part.element());
            }
        }
        for (Element payload : this.children(body, null, null)) {
            QName payloadName = new QName(this.namespace(payload), payload.getLocalName());
            if (globalParts.contains(payloadName)) {
                this.validate(payload);
            }
        }
    }

    void validatePart(Element payload, SoapPart part) throws IOException {
        if (payload == null || part == null) {
            throw new IllegalArgumentException("SOAP part payload and binding are required.");
        }

        QName payloadName = new QName(this.namespace(payload), payload.getLocalName());
        if (!payloadName.equals(part.element())) {
            throw new IOException("SOAP part QName differs from its XML Schema element: " + payloadName);
        }
        if (!this.hasElement(payloadName)) {
            throw new IOException("SOAP part references an unknown XML Schema element: " + payloadName);
        }

        // Header 控制属性不属于业务 Schema；在副本中移除，保持收到的消息不变。
        SchemaComponent element = this.elements.get(payloadName);
        Element validationPayload = this.validationCopy(payload, element);
        this.validate(validationPayload, "SOAP part");
        this.applyDefaults(payload, validationPayload);
    }

    private void validate(Element payload) throws IOException {
        this.validate(payload, "SOAP Body");
    }

    private void validate(Element payload, String description) throws IOException {
        try {
            Validator validator = this.validationSchema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // 使用同一 DOM 作为结果，保留 Schema 注入的属性及空元素默认值供 Java 映射读取。
            validator.validate(new DOMSource(payload), new DOMResult(payload));
        } catch (SAXException failure) {
            throw new IOException(description + " does not match its XML Schema: " + failure.getMessage(), failure);
        }
    }

    private void applyDefaults(Element target, Element validated) {
        // Header 在副本中移除了协议控制属性，只把新增的业务值带回原文档。
        NamedNodeMap attributes = validated.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            String namespace = attribute.getNamespaceURI();
            String localName = attribute.getLocalName();
            if (!target.hasAttributeNS(namespace, localName)) {
                target.setAttributeNS(namespace, attribute.getName(), attribute.getValue());
            }
        }
        List<Element> originals = this.children(target, null, null);
        List<Element> checked = this.children(validated, null, null);
        if (originals.isEmpty()) {
            if (target.getTextContent().isEmpty()) {
                target.setTextContent(validated.getTextContent());
            }
            return;
        }
        for (int index = 0; index < originals.size(); index++) {
            this.applyDefaults(originals.get(index), checked.get(index));
        }
    }

    private Element validationCopy(Element payload, SchemaComponent element) throws IOException {
        Document document = SoapXmlUtils.newDocument();
        Element copy = (Element) document.importNode(payload, true);
        document.appendChild(copy);
        this.copyNamespaces(payload, copy);

        NamedNodeMap attributes = copy.getAttributes();
        for (int index = attributes.getLength() - 1; index >= 0; index--) {
            Attr attribute = (Attr) attributes.item(index);
            String namespace = attribute.getNamespaceURI();
            boolean soapControl = //
                    StringUtils.equals(SoapVersion.SOAP_11.namespace(), namespace) || //
                            StringUtils.equals(SoapVersion.SOAP_12.namespace(), namespace);
            QName attributeName = new QName(namespace == null ? "" : namespace, attribute.getLocalName());
            if (soapControl && !this.acceptsAttribute(element, attributeName)) {
                copy.removeAttributeNode(attribute);
            }
        }
        return copy;
    }

    private void copyNamespaces(Element payload, Element copy) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (Node current = payload; current instanceof Element element; current = current.getParentNode()) {
            NamedNodeMap attributes = element.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                if (!StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNamespaceURI())) {
                    continue;
                }
                declarations.putIfAbsent(attribute.getName(), attribute.getValue());
            }
        }
        for (Map.Entry<String, String> declaration : declarations.entrySet()) {
            copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, declaration.getKey(), declaration.getValue());
        }
    }

    private boolean acceptsAttribute(SchemaComponent element, QName attributeName) throws IOException {
        Element inlineType = this.firstChild(element.element(), SoapWsdlParser.XSD_NAMESPACE, "complexType");
        if (inlineType != null) {
            return this.acceptsAttribute(inlineType, element.view(), attributeName, new LinkedHashSet<>(), //
                    new LinkedHashSet<>());
        }
        if (element.type() == null) {
            return false;
        }
        return this.acceptsTypeAttribute(element.type(), attributeName, new LinkedHashSet<>(), //
                new LinkedHashSet<>());
    }

    private boolean acceptsTypeAttribute(QName typeName, QName attributeName, Set<QName> resolvingTypes, //
            Set<QName> resolvingGroups) throws IOException {
        SchemaComponent type = this.types.get(typeName);
        if (type == null || !StringUtils.equals("complexType", type.element().getLocalName())) {
            return false;
        }
        if (!resolvingTypes.add(typeName)) {
            return false;
        }
        try {
            return this.acceptsAttribute(type.element(), type.view(), attributeName, resolvingTypes, resolvingGroups);
        } finally {
            resolvingTypes.remove(typeName);
        }
    }

    private boolean acceptsAttribute(Element type, SchemaView view, QName attributeName, //
            Set<QName> resolvingTypes, Set<QName> resolvingGroups) throws IOException {
        if (this.acceptsDeclaredAttribute(type, view, attributeName, resolvingTypes, resolvingGroups)) {
            return true;
        }

        for (Element content : this.children(type, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String contentName = content.getLocalName();
            boolean typeContent = StringUtils.equals("complexContent", contentName) || //
                    StringUtils.equals("simpleContent", contentName);
            if (!typeContent) {
                continue;
            }
            for (Element derivation : this.children(content, SoapWsdlParser.XSD_NAMESPACE, null)) {
                String derivationName = derivation.getLocalName();
                boolean derived = StringUtils.equals("extension", derivationName) || //
                        StringUtils.equals("restriction", derivationName);
                if (!derived) {
                    continue;
                }
                if (this.acceptsDeclaredAttribute(derivation, view, attributeName, resolvingTypes, resolvingGroups)) {
                    return true;
                }
                String base = derivation.getAttribute("base");
                if (!base.isBlank()) {
                    QName baseName = this.componentName(derivation, base, view);
                    if (this.acceptsTypeAttribute(baseName, attributeName, resolvingTypes, resolvingGroups)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean acceptsDeclaredAttribute(Element owner, SchemaView view, QName attributeName, //
            Set<QName> resolvingTypes, Set<QName> resolvingGroups) throws IOException {
        for (Element child : this.children(owner, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("attribute", localName) && this.matchesAttribute(child, view, attributeName)) {
                return true;
            }
            if (StringUtils.equals("attributeGroup", localName) && //
                    this.acceptsAttributeGroup(child, view, attributeName, resolvingTypes, resolvingGroups)) {
                return true;
            }
            if (StringUtils.equals("anyAttribute", localName) && this.acceptsNamespace(child, view, attributeName)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAttribute(Element declaration, SchemaView view, QName attributeName) throws IOException {
        String reference = declaration.getAttribute("ref");
        if (!reference.isBlank()) {
            QName referenceName = this.componentName(declaration, reference, view);
            return attributeName.equals(referenceName) && this.attributes.containsKey(referenceName);
        }

        String name = declaration.getAttribute("name");
        if (name.isBlank()) {
            return false;
        }
        String form = declaration.getAttribute("form");
        if (form.isBlank()) {
            form = view.schema().getAttribute("attributeFormDefault");
        }
        String namespace = StringUtils.equals("qualified", form) ? view.namespace() : "";
        return attributeName.equals(new QName(namespace, name));
    }

    private boolean acceptsAttributeGroup(Element declaration, SchemaView view, QName attributeName, //
            Set<QName> resolvingTypes, Set<QName> resolvingGroups) throws IOException {
        String reference = declaration.getAttribute("ref");
        if (reference.isBlank()) {
            return this.acceptsDeclaredAttribute(declaration, view, attributeName, resolvingTypes, resolvingGroups);
        }

        QName groupName = this.componentName(declaration, reference, view);
        SchemaComponent group = this.attributeGroups.get(groupName);
        if (group == null || !resolvingGroups.add(groupName)) {
            return false;
        }
        try {
            return this.acceptsDeclaredAttribute(group.element(), group.view(), attributeName, resolvingTypes, //
                    resolvingGroups);
        } finally {
            resolvingGroups.remove(groupName);
        }
    }

    private boolean acceptsNamespace(Element anyAttribute, SchemaView view, QName attributeName) {
        String constraint = anyAttribute.getAttribute("namespace");
        if (constraint.isBlank() || StringUtils.equals("##any", constraint)) {
            return true;
        }

        String namespace = attributeName.getNamespaceURI();
        for (String value : constraint.trim().split("\\s+")) {
            if (StringUtils.equals("##other", value) && !namespace.isEmpty() && //
                    !StringUtils.equals(view.namespace(), namespace)) {
                return true;
            }
            if (StringUtils.equals("##targetNamespace", value) && StringUtils.equals(view.namespace(), namespace)) {
                return true;
            }
            if (StringUtils.equals("##local", value) && namespace.isEmpty()) {
                return true;
            }
            if (StringUtils.equals(value, namespace)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, SoapXmlResource> indexResources(Collection<SoapXmlResource> source) throws IOException {
        Map<String, SoapXmlResource> indexed = new LinkedHashMap<>();
        for (SoapXmlResource resource : source) {
            if (resource == null) {
                throw new IOException("WSDL/XSD resource collection contains null.");
            }
            if (indexed.putIfAbsent(resource.name(), resource) != null) {
                throw new IOException("Duplicate WSDL/XSD resource name: " + resource.name());
            }
        }

        this.addStandardSchema(indexed, SOAP_11_SCHEMA);
        this.addStandardSchema(indexed, SOAP_12_SCHEMA);
        this.addStandardSchema(indexed, SOAP_11_ENVELOPE_SCHEMA);
        this.addStandardSchema(indexed, SOAP_12_ENVELOPE_SCHEMA);
        this.addStandardSchema(indexed, XML_NAMESPACE_SCHEMA);
        return Collections.unmodifiableMap(indexed);
    }

    private void addStandardSchema(Map<String, SoapXmlResource> indexed, String name) throws IOException {
        String resourceName = SOAP_SCHEMA_ROOT.substring(1) + name;
        URL source = SoapSchemaParser.class.getResource(SOAP_SCHEMA_ROOT + name);
        if (source == null) {
            throw new IOException("SOAP Encoding XML Schema resource is missing: " + name);
        }

        byte[] body;
        try (InputStream input = source.openStream()) {
            body = input.readAllBytes();
        }
        Document document = this.parseStandardSchema(source, body);

        // 同一标准 Schema 可由 HTTP 定义页再次导入；仅原文字节相同的资源共享索引视图。
        for (Map.Entry<String, SoapXmlResource> loaded : indexed.entrySet()) {
            SoapXmlResource imported = loaded.getValue();
            if (Arrays.equals(body, imported.body())) {
                SoapXmlResource canonical = new SoapXmlResource(imported.name(), imported.source(), body, document);
                loaded.setValue(canonical);
            }
        }
        SoapXmlResource resource = new SoapXmlResource(resourceName, source, body, document);
        if (indexed.putIfAbsent(resourceName, resource) != null) {
            throw new IOException("SOAP Encoding XML Schema resource name conflicts with WSDL input: " + resourceName);
        }
    }

    private Document parseStandardSchema(URL source, byte[] body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(body), source.toExternalForm());
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("Invalid bundled SOAP Encoding XML Schema: " + source, failure);
        }
    }

    private Schema compileSchema(SoapXmlResource entry) throws IOException {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver(new ResourceResolver(this.resources));

            List<Source> sources = this.schemaSources(entry);
            if (sources.isEmpty()) {
                return factory.newSchema();
            }
            return factory.newSchema(sources.toArray(Source[]::new));
        } catch (SAXException failure) {
            throw new IOException("Invalid XML Schema contract: " + failure.getMessage(), failure);
        }
    }

    private List<Source> schemaSources(SoapXmlResource entry) {
        List<Source> sources = new ArrayList<>();
        for (SoapXmlResource resource : this.resources.values()) {
            Element root = resource.document().getDocumentElement();
            if (resource == entry && this.isElement(root, SoapWsdlParser.XSD_NAMESPACE, "schema")) {
                sources.add(this.schemaSource(resource, root));
                continue;
            }
            if (!this.isElement(root, SoapWsdlParser.WSDL_NAMESPACE, "definitions")) {
                continue;
            }
            for (Element schema : this.descendants(root, SoapWsdlParser.XSD_NAMESPACE, "schema")) {
                sources.add(this.schemaSource(resource, schema));
            }
        }
        return sources;
    }

    private Source schemaSource(SoapXmlResource resource, Element schema) {
        DOMSource source = new DOMSource(schema);
        source.setSystemId(resource.source().toExternalForm());
        return source;
    }

    private void indexResource(SoapXmlResource resource) throws IOException {
        Element root = resource.document().getDocumentElement();
        if (this.isElement(root, SoapWsdlParser.XSD_NAMESPACE, "schema")) {
            this.indexSchema(new SchemaView(resource, root, root.getAttribute("targetNamespace")));
            return;
        }
        if (!this.isElement(root, SoapWsdlParser.WSDL_NAMESPACE, "definitions")) {
            return;
        }

        for (Element schema : this.descendants(root, SoapWsdlParser.XSD_NAMESPACE, "schema")) {
            this.indexSchema(new SchemaView(resource, schema, schema.getAttribute("targetNamespace")));
        }
    }

    private void indexSchema(SchemaView view) throws IOException {
        if (!this.markIndexed(view)) {
            return;
        }

        // 当前 Schema 的全局声明使用有效目标命名空间建立索引。
        for (Element child : this.children(view.schema(), SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("element", localName)) {
                this.indexElement(view, child);
            } else if (StringUtils.equals("complexType", localName) || StringUtils.equals("simpleType", localName)) {
                this.indexType(view, child);
            } else if (StringUtils.equals("attribute", localName)) {
                this.indexAttribute(view, child);
            } else if (StringUtils.equals("attributeGroup", localName)) {
                this.indexAttributeGroup(view, child);
            }
        }

        // include 继承包含方命名空间，import 保持被导入 Schema 的命名空间。
        for (Element include : this.children(view.schema(), SoapWsdlParser.XSD_NAMESPACE, "include")) {
            SchemaView included = this.resolveSchema(view, include, true);
            this.indexSchema(included);
        }
        for (Element imported : this.children(view.schema(), SoapWsdlParser.XSD_NAMESPACE, "import")) {
            String location = imported.getAttribute("schemaLocation");
            if (!location.isBlank()) {
                SchemaView importedView = this.resolveSchema(view, imported, false);
                this.indexSchema(importedView);
            }
        }
    }

    private void indexElement(SchemaView view, Element element) throws IOException {
        String name = this.requiredAttribute(element, "name", "XML Schema global element name");
        if (element.hasAttribute("ref")) {
            throw new IOException("XML Schema global element cannot declare ref: " + name);
        }

        QName componentName = new QName(view.namespace(), name);
        QName typeName = this.declaredType(element, view);
        SchemaComponent component = new SchemaComponent(componentName, typeName, element, view);
        this.putComponent(this.elements, component, "element");
    }

    private void indexType(SchemaView view, Element type) throws IOException {
        String name = this.requiredAttribute(type, "name", "XML Schema type name");
        QName componentName = new QName(view.namespace(), name);
        SchemaComponent component = new SchemaComponent(componentName, null, type, view);
        this.putComponent(this.types, component, "type");
    }

    private void indexAttribute(SchemaView view, Element attribute) throws IOException {
        String name = this.requiredAttribute(attribute, "name", "XML Schema attribute name");
        QName componentName = new QName(view.namespace(), name);
        SchemaComponent component = new SchemaComponent(componentName, null, attribute, view);
        this.putComponent(this.attributes, component, "attribute");
    }

    private void indexAttributeGroup(SchemaView view, Element group) throws IOException {
        String name = this.requiredAttribute(group, "name", "XML Schema attributeGroup name");
        QName componentName = new QName(view.namespace(), name);
        SchemaComponent component = new SchemaComponent(componentName, null, group, view);
        this.putComponent(this.attributeGroups, component, "attributeGroup");
    }

    private void putComponent(Map<QName, SchemaComponent> components, SchemaComponent component, //
            String description) throws IOException {
        SchemaComponent previous = components.putIfAbsent(component.name(), component);
        if (previous != null && previous.element() != component.element()) {
            throw new IOException("Duplicate XML Schema " + description + ": " + component.name());
        }
    }

    private SchemaView resolveSchema(SchemaView owner, Element reference, boolean include) throws IOException {
        if (!include) {
            String importedNamespace = reference.getAttribute("namespace");
            SoapXmlResource standard = this.standardSchema(importedNamespace);
            if (standard != null) {
                Element standardSchema = standard.document().getDocumentElement();
                return new SchemaView(standard, standardSchema, importedNamespace);
            }
        }

        String location = this.requiredAttribute(reference, "schemaLocation", "XML Schema location");
        SoapXmlResource resource = this.referencedResource(owner.resource(), location);
        if (resource == null) {
            throw new IOException("XML Schema resource was not loaded: " + location);
        }
        String resourceName = resource.name();

        Element schema = resource.document().getDocumentElement();
        if (!this.isElement(schema, SoapWsdlParser.XSD_NAMESPACE, "schema")) {
            throw new IOException("XML Schema location does not identify a schema document: " + resourceName);
        }

        String declaredNamespace = schema.getAttribute("targetNamespace");
        if (include) {
            if (!declaredNamespace.isBlank() && !StringUtils.equals(declaredNamespace, owner.namespace())) {
                throw new IOException("Included XML Schema targetNamespace differs from its owner: " + resourceName);
            }
            String namespace = declaredNamespace.isBlank() ? owner.namespace() : declaredNamespace;
            return new SchemaView(resource, schema, namespace);
        }

        String importedNamespace = reference.getAttribute("namespace");
        if (!StringUtils.equals(importedNamespace, declaredNamespace)) {
            throw new IOException("Imported XML Schema namespace does not match targetNamespace: " + resourceName);
        }
        return new SchemaView(resource, schema, declaredNamespace);
    }

    private SoapXmlResource referencedResource(SoapXmlResource owner, String location) throws IOException {
        String protocol = owner.source().getProtocol();
        boolean http = StringUtils.equalsIgnoreCase("http", protocol);
        boolean https = StringUtils.equalsIgnoreCase("https", protocol);
        if (!http && !https) {
            return this.resources.get(this.resolveName(owner.name(), location));
        }
        try {
            // HTTP 资源可能由同一路径的不同查询参数发布；以已加载的实际 URL 匹配。
            URI resolved = resolveUri(owner.source().toURI(), new URI(location));
            for (SoapXmlResource resource : this.resources.values()) {
                if (resolved.equals(resource.source().toURI().normalize())) {
                    return resource;
                }
            }
            return null;
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid XML Schema resource URL: " + location, failure);
        }
    }

    private static URI resolveUri(URI owner, URI reference) {
        boolean relative = !reference.isAbsolute() && reference.getRawAuthority() == null;
        String path = reference.getRawPath();
        boolean queryOnly = relative && path.isEmpty() && reference.getRawQuery() != null;
        if (!queryOnly) {
            return owner.resolve(reference).normalize();
        }

        // JDK URI.resolve 对 query-only 引用会删掉文件名；同端点挂载必须保留原路径。
        String address = owner.toASCIIString();
        int fragment = address.indexOf('#');
        if (fragment >= 0) {
            address = address.substring(0, fragment);
        }
        int query = address.indexOf('?');
        if (query >= 0) {
            address = address.substring(0, query);
        }
        return URI.create(address + "?" + reference.getRawQuery()).normalize();
    }

    private SoapXmlResource standardSchema(String namespace) {
        String name = null;
        if (StringUtils.equals(SOAP_11_ENCODING, namespace)) {
            name = SOAP_11_SCHEMA;
        } else if (StringUtils.equals(SOAP_12_ENCODING, namespace)) {
            name = SOAP_12_SCHEMA;
        } else if (StringUtils.equals(SOAP_11_ENVELOPE, namespace)) {
            name = SOAP_11_ENVELOPE_SCHEMA;
        } else if (StringUtils.equals(SOAP_12_ENVELOPE, namespace)) {
            name = SOAP_12_ENVELOPE_SCHEMA;
        } else if (StringUtils.equals(XML_NAMESPACE, namespace)) {
            name = XML_NAMESPACE_SCHEMA;
        }
        if (name == null) {
            return null;
        }
        return this.resources.get(SOAP_SCHEMA_ROOT.substring(1) + name);
    }

    private boolean markIndexed(SchemaView view) {
        Set<String> namespaces = this.indexedViews.computeIfAbsent(view.schema(), key -> new LinkedHashSet<>());
        return namespaces.add(view.namespace());
    }

    private WrapperDefinition wrapper(QName wrapperName) throws IOException {
        WrapperDefinition cached = this.wrappers.get(wrapperName);
        if (cached != null) {
            return cached;
        }

        SchemaComponent wrapper = this.elements.get(wrapperName);
        if (wrapper == null) {
            throw new IOException("SOAP body references an unknown XML Schema element: " + wrapperName);
        }

        Element complexType = this.firstChild(wrapper.element(), SoapWsdlParser.XSD_NAMESPACE, "complexType");
        SchemaView view = wrapper.view();
        QName namedType = wrapper.type();
        if (complexType == null && namedType != null) {
            SchemaComponent type = this.types.get(namedType);
            if (type == null) {
                WrapperDefinition bare = WrapperDefinition.bare();
                this.wrappers.put(wrapperName, bare);
                return bare;
            }
            complexType = type.element();
            view = type.view();
        }
        if (complexType == null) {
            WrapperDefinition bare = WrapperDefinition.bare();
            this.wrappers.put(wrapperName, bare);
            return bare;
        }

        ParticleResult result = new ParticleResult();
        Set<QName> resolvingTypes = new LinkedHashSet<>();
        if (namedType != null) {
            resolvingTypes.add(namedType);
        }
        this.collectComplexType(complexType, view, result, resolvingTypes);

        WrapperDefinition parsed = new WrapperDefinition(result.parts(), result.choices());
        this.wrappers.put(wrapperName, parsed);
        return parsed;
    }

    private void collectComplexType(Element complexType, SchemaView view, ParticleResult result, //
            Set<QName> resolvingTypes) throws IOException {
        this.requireElementOnly(complexType);
        for (Element child : this.children(complexType, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("annotation", localName)) {
                continue;
            }
            if (this.isCompositor(localName)) {
                this.collectParticle(child, view, result);
            } else if (StringUtils.equals("complexContent", localName)) {
                this.collectComplexContent(child, view, result, resolvingTypes);
            } else {
                throw this.unsupported(child, "SOAP wrapper complexType");
            }
        }
    }

    private void collectComplexContent(Element complexContent, SchemaView view, ParticleResult result, //
            Set<QName> resolvingTypes) throws IOException {
        this.requireElementOnly(complexContent);
        Element extension = null;
        for (Element child : this.children(complexContent, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("annotation", localName)) {
                continue;
            }
            if (!StringUtils.equals("extension", localName) || extension != null) {
                throw this.unsupported(child, "SOAP wrapper complexContent");
            }
            extension = child;
        }
        if (extension == null) {
            throw new SoapSchemaMappingException("SOAP wrapper complexContent requires one extension.");
        }

        // XSD extension 的有效内容模型是基类型粒子后接扩展粒子。
        ParticleResult baseResult = new ParticleResult();
        String baseValue = this.requiredAttribute(extension, "base", "XML Schema extension base");
        QName baseName = this.componentName(extension, baseValue, view);
        if (!ANY_TYPE.equals(baseName)) {
            SchemaComponent base = this.types.get(baseName);
            if (base == null) {
                throw new IOException("XML Schema extension references an unknown complexType: " + baseName);
            }
            if (!resolvingTypes.add(baseName)) {
                throw new IOException("Cyclic XML Schema complexType extension: " + baseName);
            }
            this.collectComplexType(base.element(), base.view(), baseResult, resolvingTypes);
            resolvingTypes.remove(baseName);
        }

        ParticleResult extensionResult = new ParticleResult();
        for (Element child : this.children(extension, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("annotation", localName)) {
                continue;
            }
            if (!this.isCompositor(localName)) {
                throw this.unsupported(child, "SOAP wrapper complexContent extension");
            }
            this.collectParticle(child, view, extensionResult);
        }
        result.append(baseResult);
        result.append(extensionResult);
    }

    private void collectParticle(Element particle, SchemaView view, ParticleResult result) throws IOException {
        String compositor = particle.getLocalName();
        Occurrence occurrence = this.occurrence(particle);
        if (occurrence.maximum() == 0) {
            return;
        }
        if (StringUtils.equals("choice", compositor)) {
            this.collectChoice(particle, view, occurrence, result);
            return;
        }
        if (occurrence.minimum() != 1 || occurrence.maximum() != 1) {
            String message = "XML Schema " + compositor + " occurrence cannot map to fixed Java parameters.";
            throw new SoapSchemaMappingException(message);
        }

        for (Element child : this.children(particle, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("annotation", localName)) {
                continue;
            }
            if (StringUtils.equals("element", localName)) {
                this.collectElement(child, view, result);
            } else if (StringUtils.equals("sequence", compositor) && this.isNestedCompositor(localName)) {
                this.collectParticle(child, view, result);
            } else {
                throw this.unsupported(child, "XML Schema " + compositor + " particle");
            }
        }
    }

    private void collectChoice(Element choice, SchemaView view, Occurrence occurrence, //
            ParticleResult result) throws IOException {
        if (occurrence.maximum() != 1 || occurrence.minimum() > 1) {
            throw new SoapSchemaMappingException("Repeated XML Schema choice cannot map to fixed Java parameters.");
        }

        int effectiveMinimum = occurrence.minimum();
        List<Integer> indexes = new ArrayList<>();
        for (Element child : this.children(choice, SoapWsdlParser.XSD_NAMESPACE, null)) {
            String localName = child.getLocalName();
            if (StringUtils.equals("annotation", localName)) {
                continue;
            }
            if (!StringUtils.equals("element", localName)) {
                throw this.unsupported(child, "XML Schema choice branch");
            }

            Occurrence branch = this.occurrence(child);
            if (branch.minimum() > 1) {
                throw new SoapSchemaMappingException("XML Schema choice branch minOccurs cannot exceed 1.");
            }
            if (branch.maximum() == 0) {
                effectiveMinimum = 0;
                continue;
            }
            if (branch.minimum() == 0) {
                effectiveMinimum = 0;
            }

            SoapPart part = this.part(child, view, 0, branch.maximum());
            indexes.add(result.addPart(part));
        }
        if (indexes.isEmpty()) {
            throw new SoapSchemaMappingException("XML Schema choice must declare at least one usable element branch.");
        }
        result.addChoice(new SoapChoice(effectiveMinimum, indexes));
    }

    private void collectElement(Element element, SchemaView view, ParticleResult result) throws IOException {
        Occurrence occurrence = this.occurrence(element);
        if (occurrence.maximum() == 0) {
            return;
        }
        result.addPart(this.part(element, view, occurrence.minimum(), occurrence.maximum()));
    }

    private SoapPart part(Element element, SchemaView view, int minimum, int maximum) throws IOException {
        String name = element.getAttribute("name");
        String reference = element.getAttribute("ref");
        if (!name.isBlank() && !reference.isBlank()) {
            throw new IOException("XML Schema local element cannot declare both name and ref.");
        }

        QName elementName;
        QName typeName;
        if (!reference.isBlank()) {
            if (element.hasAttribute("form")) {
                throw new IOException("XML Schema element ref cannot declare form.");
            }
            elementName = this.componentName(element, reference, view);
            SchemaComponent referenced = this.elements.get(elementName);
            if (referenced == null) {
                throw new IOException("XML Schema element ref is unknown: " + elementName);
            }
            typeName = referenced.type();
            name = elementName.getLocalPart();
        } else {
            if (name.isBlank()) {
                throw new IOException("XML Schema local element requires name or ref.");
            }
            elementName = this.localElementName(element, name, view);
            typeName = this.declaredType(element, view);
        }
        return new SoapPart(name, elementName, typeName, minimum, maximum);
    }

    private QName localElementName(Element element, String name, SchemaView view) throws IOException {
        String form = element.getAttribute("form");
        if (form.isBlank()) {
            form = view.schema().getAttribute("elementFormDefault");
        }
        if (form.isBlank() || StringUtils.equals("unqualified", form)) {
            return new QName("", name);
        }
        if (StringUtils.equals("qualified", form)) {
            return new QName(view.namespace(), name);
        }
        throw new IOException("XML Schema element form must be qualified or unqualified: " + form);
    }

    private QName declaredType(Element element, SchemaView view) throws IOException {
        String type = element.getAttribute("type");
        if (type.isBlank()) {
            return null;
        }
        if (this.firstChild(element, SoapWsdlParser.XSD_NAMESPACE, "complexType") != null || //
                this.firstChild(element, SoapWsdlParser.XSD_NAMESPACE, "simpleType") != null) {
            throw new IOException("XML Schema element cannot declare type and an inline type together.");
        }
        return this.componentName(element, type, view);
    }

    private QName componentName(Element context, String value, SchemaView view) throws IOException {
        String lexical = value.trim();
        int separator = lexical.indexOf(':');
        String prefix = separator < 0 ? null : lexical.substring(0, separator);
        String localName = separator < 0 ? lexical : lexical.substring(separator + 1);
        try {
            SoapXmlUtils.requireName(context.getOwnerDocument(), localName, "QName local name");
            if (prefix != null) {
                SoapXmlUtils.requireName(context.getOwnerDocument(), prefix, "QName prefix");
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid XML QName: " + value, failure);
        }

        String namespace = context.lookupNamespaceURI(prefix);
        if (prefix != null && namespace == null) {
            throw new IOException("Undeclared XML QName prefix: " + prefix);
        }
        if (prefix == null && StringUtils.isBlank(namespace) && view.chameleon()) {
            namespace = view.namespace();
        }
        return new QName(namespace == null ? "" : namespace, localName);
    }

    private Occurrence occurrence(Element particle) throws IOException {
        int minimum = this.occurrenceValue(particle, "minOccurs", 1, false);
        int maximum = this.occurrenceValue(particle, "maxOccurs", 1, true);
        if (maximum >= 0 && minimum > maximum) {
            throw new IOException("XML Schema minOccurs cannot exceed maxOccurs.");
        }
        return new Occurrence(minimum, maximum);
    }

    private int occurrenceValue(Element particle, String attribute, int defaultValue, //
            boolean unbounded) throws IOException {
        String value = particle.getAttribute(attribute);
        if (value.isBlank()) {
            return defaultValue;
        }
        if (unbounded && StringUtils.equals("unbounded", value)) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative occurrence");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid XML Schema " + attribute + ": " + value, failure);
        }
    }

    private void requireElementOnly(Element element) throws IOException {
        String mixed = element.getAttribute("mixed");
        if (!mixed.isBlank() && !StringUtils.equals("false", mixed) && !StringUtils.equals("0", mixed)) {
            throw new SoapSchemaMappingException("Mixed XML Schema content cannot map to SOAP method parameters.");
        }
    }

    private SoapSchemaMappingException unsupported(Element element, String location) {
        String message = "Unsupported " + location + " structure: " + element.getLocalName();
        return new SoapSchemaMappingException(message);
    }

    private boolean isCompositor(String localName) {
        return StringUtils.equals("sequence", localName) || StringUtils.equals("all", localName) || //
                StringUtils.equals("choice", localName);
    }

    private boolean isNestedCompositor(String localName) {
        return StringUtils.equals("sequence", localName) || StringUtils.equals("choice", localName);
    }

    private Element firstChild(Element parent, String namespace, String localName) {
        for (Element child : this.children(parent, namespace, localName)) {
            return child;
        }
        return null;
    }

    private Element child(Element parent, QName name) {
        for (Element child : this.children(parent, null, null)) {
            String childNamespace = this.namespace(child);
            if (StringUtils.equals(name.getNamespaceURI(), childNamespace) && //
                    StringUtils.equals(name.getLocalPart(), child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private List<Element> children(Element parent, String namespace, String localName) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && this.isElement(child, namespace, localName)) {
                elements.add(child);
            }
        }
        return elements;
    }

    private List<Element> descendants(Element parent, String namespace, String localName) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        for (int index = 0; index < nodes.getLength(); index++) {
            elements.add((Element) nodes.item(index));
        }
        return elements;
    }

    private String namespace(Element element) {
        String namespace = element.getNamespaceURI();
        return namespace == null ? "" : namespace;
    }

    private boolean isElement(Element element, String namespace, String localName) {
        boolean namespaceMatch = namespace == null || StringUtils.equals(namespace, element.getNamespaceURI());
        return namespaceMatch && (localName == null || StringUtils.equals(localName, element.getLocalName()));
    }

    private String requiredAttribute(Element element, String name, String description) throws IOException {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new IOException(description + " is required.");
        }
        return value;
    }

    private String resolveName(String currentName, String location) throws IOException {
        try {
            URI reference = new URI(location);
            if (reference.isAbsolute() || reference.getRawAuthority() != null || reference.getRawQuery() != null || //
                    reference.getRawFragment() != null || reference.getPath() == null || //
                    reference.getPath().startsWith("/") || reference.getPath().indexOf('\\') >= 0) {
                throw new IOException("XML Schema location must be a relative resource name: " + location);
            }

            URI base = new URI(null, null, "/" + currentName, null);
            String path = base.resolve(reference).normalize().getPath();
            if (path == null || !path.startsWith("/") || path.startsWith("/../")) {
                throw new IOException("XML Schema location escapes its resource root: " + location);
            }
            return path.substring(1);
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid XML Schema location: " + location, failure);
        }
    }

    /** 单个 Schema 在目标命名空间中的有效视图。 */
    private static final class SchemaView {
        private final SoapXmlResource resource;
        private final Element         schema;
        private final String          namespace;

        private SchemaView(SoapXmlResource resource, Element schema, String namespace) {
            this.resource = resource;
            this.schema = schema;
            this.namespace = namespace;
        }

        public SoapXmlResource resource() {
            return this.resource;
        }

        public Element schema() {
            return this.schema;
        }

        public String namespace() {
            return this.namespace;
        }

        private boolean chameleon() {
            return this.schema.getAttribute("targetNamespace").isBlank() && !this.namespace.isBlank();
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof SchemaView that)) {
                return false;
            }
            return ObjectUtils.equals(this.resource, that.resource) && //
                    ObjectUtils.equals(this.schema, that.schema) && //
                    StringUtils.equals(this.namespace, that.namespace);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.resource, this.schema, this.namespace);
        }

        @Override
        public String toString() {
            return "SchemaView[resource=" + this.resource + ", schema=" + this.schema + //
                    ", namespace=" + this.namespace + "]";
        }
    }

    /** 一个已建立 QName 索引的全局 Schema 组件。 */
    private static final class SchemaComponent {
        private final QName      name;
        private final QName      type;
        private final Element    element;
        private final SchemaView view;

        private SchemaComponent(QName name, QName type, Element element, SchemaView view) {
            this.name = name;
            this.type = type;
            this.element = element;
            this.view = view;
        }

        public QName name() {
            return this.name;
        }

        public QName type() {
            return this.type;
        }

        public Element element() {
            return this.element;
        }

        public SchemaView view() {
            return this.view;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof SchemaComponent that)) {
                return false;
            }
            return ObjectUtils.equals(this.name, that.name) && //
                    ObjectUtils.equals(this.type, that.type) && //
                    ObjectUtils.equals(this.element, that.element) && //
                    ObjectUtils.equals(this.view, that.view);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.type, this.element, this.view);
        }

        @Override
        public String toString() {
            return "SchemaComponent[name=" + this.name + ", type=" + this.type + ", element=" + this.element + //
                    ", view=" + this.view + "]";
        }
    }

    /** 单个粒子的基数。 */
    private static final class Occurrence {
        private final int minimum;
        private final int maximum;

        private Occurrence(int minimum, int maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public int minimum() {
            return this.minimum;
        }

        public int maximum() {
            return this.maximum;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Occurrence that)) {
                return false;
            }
            return this.minimum == that.minimum && this.maximum == that.maximum;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.minimum, this.maximum);
        }

        @Override
        public String toString() {
            return "Occurrence[minimum=" + this.minimum + ", maximum=" + this.maximum + "]";
        }
    }

    /** 包装体粒子的解析结果。 */
    private static final class ParticleResult {
        private final List<SoapPart>   parts;
        private final List<SoapChoice> choices;

        private ParticleResult() {
            this.parts = new ArrayList<>();
            this.choices = new ArrayList<>();
        }

        private int addPart(SoapPart part) {
            this.parts.add(part);
            return this.parts.size() - 1;
        }

        private void addChoice(SoapChoice choice) {
            this.choices.add(choice);
        }

        private void append(ParticleResult addition) {
            if (addition.parts.isEmpty()) {
                return;
            }
            int offset = this.parts.size();
            this.parts.addAll(addition.parts);
            for (SoapChoice choice : addition.choices) {
                List<Integer> shifted = new ArrayList<>(choice.partIndexes().size());
                for (Integer partIndex : choice.partIndexes()) {
                    shifted.add(partIndex + offset);
                }
                this.choices.add(new SoapChoice(choice.minOccurs(), shifted));
            }
        }

        private List<SoapPart> parts() {
            return List.copyOf(this.parts);
        }

        private List<SoapChoice> choices() {
            return List.copyOf(this.choices);
        }

    }

    /** 缓存的包装体解析结果，parts 为 null 表示 bare element。 */
    private static final class WrapperDefinition {
        private final List<SoapPart>   parts;
        private final List<SoapChoice> choices;

        private WrapperDefinition(List<SoapPart> parts, List<SoapChoice> choices) {
            this.parts = parts == null ? null : List.copyOf(parts);
            this.choices = List.copyOf(choices);
        }

        public List<SoapPart> parts() {
            return this.parts;
        }

        public List<SoapChoice> choices() {
            return this.choices;
        }

        private static WrapperDefinition bare() {
            return new WrapperDefinition(null, List.of());
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof WrapperDefinition that)) {
                return false;
            }
            return ObjectUtils.equals(this.parts, that.parts) && ObjectUtils.equals(this.choices, that.choices);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.parts, this.choices);
        }

        @Override
        public String toString() {
            return "WrapperDefinition[parts=" + this.parts + ", choices=" + this.choices + "]";
        }
    }

    /** 只从已加载资源图解析 Schema 引用。 */
    private static final class ResourceResolver implements LSResourceResolver {
        private final Map<String, SoapXmlResource> systemResources;
        private final Map<String, SoapXmlResource> namespaceResources;
        private final Map<String, SoapXmlResource> standardResources;

        private ResourceResolver(Map<String, SoapXmlResource> resources) throws IOException {
            this.systemResources = new LinkedHashMap<>();
            this.namespaceResources = new LinkedHashMap<>();
            this.standardResources = new LinkedHashMap<>();
            Set<String> ambiguousNamespaces = new LinkedHashSet<>();
            for (SoapXmlResource resource : resources.values()) {
                String systemId;
                try {
                    systemId = resource.source().toURI().normalize().toString();
                } catch (URISyntaxException failure) {
                    throw new IOException("Invalid XML Schema resource URL: " + resource.source(), failure);
                }
                this.systemResources.put(systemId, resource);

                Element root = resource.document().getDocumentElement();
                boolean schema = StringUtils.equals(SoapWsdlParser.XSD_NAMESPACE, root.getNamespaceURI()) && //
                        StringUtils.equals("schema", root.getLocalName());
                if (schema) {
                    this.indexNamespace(resource, ambiguousNamespaces);
                    continue;
                }

                // WSDL 的各内嵌 Schema 是独立 namespace 的入口，导入仍只解析已加载的文档。
                NodeList inlineSchemas = root.getElementsByTagNameNS(SoapWsdlParser.XSD_NAMESPACE, "schema");
                for (int index = 0; index < inlineSchemas.getLength(); index++) {
                    Element inlineSchema = (Element) inlineSchemas.item(index);
                    SoapXmlResource inline = this.inlineResource(resource, inlineSchema, index);
                    this.indexNamespace(inline, ambiguousNamespaces);
                }
            }
            for (String namespace : ambiguousNamespaces) {
                this.namespaceResources.remove(namespace);
            }
        }

        private void indexNamespace(SoapXmlResource resource, Set<String> ambiguousNamespaces) {
            String namespace = resource.document().getDocumentElement().getAttribute("targetNamespace");
            if (this.isStandard(resource.name(), namespace)) {
                this.standardResources.put(namespace, resource);
            } else if (this.namespaceResources.putIfAbsent(namespace, resource) != null) {
                ambiguousNamespaces.add(namespace);
            }
        }

        private SoapXmlResource inlineResource(SoapXmlResource resource, Element schema, int index) throws IOException {
            Document document = SoapXmlUtils.newDocument();
            Element copy = (Element) document.importNode(schema, true);
            document.appendChild(copy);

            // Schema 可以引用 WSDL 祖先节点声明的前缀；独立解析副本时补齐这些声明。
            for (Node node = schema; node instanceof Element ancestor; node = node.getParentNode()) {
                NamedNodeMap attributes = ancestor.getAttributes();
                for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                    Attr attribute = (Attr) attributes.item(attributeIndex);
                    String attributeNamespace = attribute.getNamespaceURI();
                    String localName = attribute.getLocalName();
                    boolean namespace = StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attributeNamespace);
                    if (namespace && !copy.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, localName)) {
                        String attributeName = attribute.getName();
                        copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attributeName, attribute.getValue());
                    }
                }
            }
            try {
                TransformerFactory factory = TransformerFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                factory.newTransformer().transform(new DOMSource(document), new StreamResult(bytes));
                URL source = new URL(resource.source().toExternalForm() + "#schema-" + index);
                return new SoapXmlResource(resource.name() + "#schema-" + index, source, bytes.toByteArray(), document);
            } catch (TransformerException failure) {
                throw new IOException("Unable to prepare inline XML Schema: " + resource.name(), failure);
            }
        }

        @Override
        public LSInput resolveResource(String type, String namespaceUri, String publicId, String systemId, //
                String baseUri) {
            String namespace = namespaceUri == null ? "" : namespaceUri;
            SoapXmlResource resource = null;

            // SOAP Encoding 是受控的标准资源，即使契约填写官方 URL 也始终离线解析。
            if (this.standardResources.containsKey(namespace)) {
                resource = this.standardResources.get(namespace);
            } else if (StringUtils.isNotBlank(systemId)) {
                try {
                    URI reference = new URI(systemId);
                    URI resolved = StringUtils.isBlank(baseUri) ? reference : resolveUri(new URI(baseUri), reference);
                    resource = this.systemResources.get(resolved.normalize().toString());
                } catch (URISyntaxException ignored) {
                    return null;
                }
            } else {
                resource = this.namespaceResources.get(namespace);
            }
            return resource == null ? null : new ResourceInput(publicId, resource);
        }

        private boolean isStandard(String name, String namespace) {
            String standardRoot = SOAP_SCHEMA_ROOT.substring(1);
            if (StringUtils.equals(standardRoot + SOAP_11_SCHEMA, name)) {
                return StringUtils.equals(SOAP_11_ENCODING, namespace);
            }
            if (StringUtils.equals(standardRoot + SOAP_12_SCHEMA, name)) {
                return StringUtils.equals(SOAP_12_ENCODING, namespace);
            }
            if (StringUtils.equals(standardRoot + SOAP_11_ENVELOPE_SCHEMA, name)) {
                return StringUtils.equals(SOAP_11_ENVELOPE, namespace);
            }
            if (StringUtils.equals(standardRoot + SOAP_12_ENVELOPE_SCHEMA, name)) {
                return StringUtils.equals(SOAP_12_ENVELOPE, namespace);
            }
            if (StringUtils.equals(standardRoot + XML_NAMESPACE_SCHEMA, name)) {
                return StringUtils.equals(XML_NAMESPACE, namespace);
            }
            return false;
        }
    }

    /** 已加载 Schema 资源的 DOM LS 输入。 */
    private static final class ResourceInput implements LSInput {
        private final byte[]  body;
        private       String  publicId;
        private       String  systemId;
        private       String  baseUri;
        private       String  encoding;
        private       boolean certifiedText;

        private ResourceInput(String publicId, SoapXmlResource resource) {
            this.body = resource.body();
            this.publicId = publicId;
            this.systemId = resource.source().toExternalForm();
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
        }

        @Override
        public InputStream getByteStream() {
            return new ByteArrayInputStream(this.body);
        }

        @Override
        public void setByteStream(InputStream byteStream) {
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {
        }

        @Override
        public String getSystemId() {
            return this.systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        @Override
        public String getPublicId() {
            return this.publicId;
        }

        @Override
        public void setPublicId(String publicId) {
            this.publicId = publicId;
        }

        @Override
        public String getBaseURI() {
            return this.baseUri;
        }

        @Override
        public void setBaseURI(String baseUri) {
            this.baseUri = baseUri;
        }

        @Override
        public String getEncoding() {
            return this.encoding;
        }

        @Override
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        @Override
        public boolean getCertifiedText() {
            return this.certifiedText;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
            this.certifiedText = certifiedText;
        }
    }
}
