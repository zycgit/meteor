/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 从已发布 Java 服务生成调用契约与 WSDL/XSD，不依赖调用方提供描述文件。 */
final class SoapGeneratedDefinitionResource extends SoapDefinitionResource {
    private static final String                WSDL_NAMESPACE = SoapWsdlParser.WSDL_NAMESPACE;
    private static final String                XSD_NAMESPACE  = SoapWsdlParser.XSD_NAMESPACE;
    private final        SoapDefinition        definition;
    private final        Document              wsdl;
    private final        Map<String, Document> schemaResources;
    private final        String                style;
    private final        String                use;
    private final        boolean               bare;
    private final        SoapSchemaParser      schemas;

    SoapGeneratedDefinitionResource(MetBindInfo<?> service, ProtocolConfig config) {
        SoapVersion version = SoapVersion.from(config);
        this.style = this.option(config, "soapStyle", "document", Set.of("document", "rpc"));
        this.use = this.option(config, "soapUse", "literal", Set.of("literal", "encoded"));
        boolean encodedDocument = StringUtils.equals("document", this.style) && StringUtils.equals("encoded", this.use);
        String defaultParameters = encodedDocument ? "bare" : "wrapped";
        String parameters = this.option(config, "soapParameterStyle", defaultParameters, Set.of("wrapped", "bare"));
        this.bare = StringUtils.equals("bare", parameters);
        if (StringUtils.equals("rpc", this.style) && this.bare) {
            throw new IllegalArgumentException("RPC SOAP style requires wrapped parameters");
        }
        if (encodedDocument && !this.bare) {
            throw new IllegalArgumentException("Document encoded SOAP requires bare parameters");
        }
        String alias = service.getAliasName("soap");
        this.wsdl = SoapXmlUtils.newDocument();
        SoapXmlUtils.requireName(this.wsdl, alias, "SOAP service alias");
        String namespace = "urn:hasor:meteor:soap:" + alias;

        // 方法顺序和参数名称与编译器保留的源码参数名无关，两端可独立生成相同契约。
        Method[] methods = MetRuntimeUtils.getServiceMethods(service.getBindType());
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        SoapVersion encodingVersion = StringUtils.equals("encoded", this.use) ? version : null;
        SoapSchemaGenerator schemaGenerator = new SoapSchemaGenerator(namespace, encodingVersion);
        Set<String> names = new HashSet<>();
        Set<String> wrappers = new HashSet<>();
        List<SoapOperation> operations = new ArrayList<>();
        for (Method method : methods) {
            String name = method.getName();
            SoapXmlUtils.requireName(this.wsdl, name, "SOAP operation");
            if (!names.add(name)) {
                throw new IllegalArgumentException("SOAP service does not support overloaded method: " + name);
            }
            boolean oneWay = service.isMessage() || method.isAnnotationPresent(MetMessage.class);
            if (oneWay && method.getReturnType() != void.class) {
                throw new IllegalArgumentException("SOAP one-way method must return void: " + name);
            }
            if (StringUtils.equals("document", this.style) && !this.bare) {
                this.requireWrapper(wrappers, name);
                if (!oneWay) {
                    this.requireWrapper(wrappers, name + "Response");
                }
            }
            schemaGenerator.operation(method, oneWay);
            SoapBody input = this.generatedBody(schemaGenerator, namespace, method, false, version, wrappers);
            SoapBody output = oneWay ? null //
                    : this.generatedBody(schemaGenerator, namespace, method, true, version, wrappers);
            String action = namespace + "/" + name;
            operations.add(new SoapOperation(name, action, this.style, input, output, true, //
                    List.of(), List.of(), Map.of()));
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("SOAP service must declare at least one operation");
        }
        this.definition = new SoapDefinition(version, namespace, operations);
        this.buildWsdl(alias, namespace, schemaGenerator.documents());
        try {
            Map<String, byte[]> documents = new LinkedHashMap<>();
            for (Map.Entry<String, Document> document : schemaGenerator.documents().entrySet()) {
                documents.put(document.getKey(), serialize(document.getValue()));
            }
            this.schemaResources = Collections.unmodifiableMap(schemaGenerator.documents());
            this.schemas = SoapSchemaParser.generated(this.schemaResources, documents);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to generate SOAP schema", failure);
        }
    }

    @Override
    public SoapDefinition definition() {
        return this.definition;
    }

    @Override
    public void validateBody(Element body, SoapBody binding) throws IOException {
        this.schemas.validateBody(body, binding);
    }

    @Override
    public void validatePart(Element payload, SoapPart part) throws IOException {
        this.schemas.validatePart(payload, part);
    }

    @Override
    public byte[] renderWsdl(URI endpoint) throws IOException {
        // 同一个契约可以挂载多个端点，实际地址只在渲染当前请求时写入副本。
        Document document = (Document) this.wsdl.cloneNode(true);
        String bindingNamespace = this.definition.version().bindingNamespace();
        Element address = (Element) document.getElementsByTagNameNS(bindingNamespace, "address").item(0);
        URI publishedEndpoint = this.endpoint(endpoint);
        address.setAttribute("location", publishedEndpoint.toASCIIString());
        this.rewriteSchemaLocations(document, publishedEndpoint);
        return serialize(document);
    }

    @Override
    public byte[] renderResource(String name, URI endpoint) throws IOException {
        Document resource = this.schemaResources.get(name);
        if (resource != null) {
            Document document = (Document) resource.cloneNode(true);
            this.rewriteSchemaLocations(document, this.endpoint(endpoint));
            return serialize(document);
        }
        Set<String> standards = Set.of("xml-namespace.xsd", "soap-encoding-1.1.xsd", "soap-encoding-1.2.xsd");
        if (standards.contains(name)) {
            String resourceName = "/META-INF/meteor/protocol/soap/" + name;
            try (InputStream input = SoapGeneratedDefinitionResource.class.getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new IOException("SOAP standard XML Schema is missing: " + name);
                }
                return input.readAllBytes();
            }
        }
        return StringUtils.equals("service.wsdl", name) ? this.renderWsdl(endpoint) : null;
    }

    private URI endpoint(URI endpoint) throws IOException {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.isOpaque()) {
            throw new IOException("SOAP endpoint must be an absolute hierarchical URI.");
        }
        try {
            return new URI(endpoint.getScheme(), endpoint.getAuthority(), endpoint.getPath(), null, null);
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid SOAP endpoint: " + endpoint, failure);
        }
    }

    private void rewriteSchemaLocations(Document document, URI endpoint) {
        var imports = document.getElementsByTagNameNS(XSD_NAMESPACE, "import");
        for (int index = 0; index < imports.getLength(); index++) {
            Element imported = (Element) imports.item(index);
            String location = imported.getAttribute("schemaLocation");
            if (!location.isEmpty()) {
                String encoded = URLEncoder.encode(location, StandardCharsets.UTF_8).replace("+", "%20");
                imported.setAttribute("schemaLocation", endpoint.toASCIIString() + "?xsd=" + encoded);
            }
        }
    }

    private String option(ProtocolConfig config, String name, String defaultValue, Set<String> values) {
        String selected = config.option(name, defaultValue);
        if (!values.contains(selected)) {
            throw new IllegalArgumentException("Unsupported SOAP " + name + ": " + selected);
        }
        return selected;
    }

    private SoapBody generatedBody(SoapSchemaGenerator generator, String namespace, Method method, boolean output, //
            SoapVersion version, Set<String> names) {
        String operation = method.getName();
        Document schemaDocument = generator.document();
        String wrapperName = output ? operation + "Response" : operation;
        Element schema = schemaDocument.getDocumentElement();
        Element wrapper = null;
        for (Node node = schema.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && StringUtils.equals("element", element.getLocalName()) //
                    && StringUtils.equals(wrapperName, element.getAttribute("name"))) {
                wrapper = element;
                break;
            }
        }
        if (wrapper == null) {
            throw new IllegalStateException("Generated SOAP schema is missing wrapper: " + wrapperName);
        }
        Element complex = (Element) wrapper.getFirstChild();
        Element sequence = (Element) complex.getFirstChild();
        List<SoapPart> parts = new ArrayList<>();
        int parameter = 0;
        for (Node node = sequence.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            QName valueName = generator.elementName(element);
            QName type = generator.elementType(element);
            String name = valueName.getLocalPart();
            if (this.bare) {
                MetXmlElement annotation;
                if (output) {
                    annotation = method.getAnnotation(MetXmlElement.class);
                } else {
                    annotation = method.getParameters()[parameter].getAnnotation(MetXmlElement.class);
                }
                boolean explicitName = false;
                if (annotation != null) {
                    explicitName = !annotation.name().isEmpty() || !annotation.namespace().isEmpty();
                }
                if (!explicitName) {
                    String globalName = output ? operation + "Result" : operation + "Arg" + parameter;
                    this.requireWrapper(names, globalName);
                    valueName = new QName(namespace, globalName);
                }
                generator.promote(element, valueName);
                if (StringUtils.equals("encoded", this.use)) {
                    name = valueName.getLocalPart();
                }
            }
            int minimum = StringUtils.equals("0", element.getAttribute("minOccurs")) ? 0 : 1;
            parts.add(new SoapPart(name, valueName, type, minimum, 1));
            parameter++;
        }
        QName bodyWrapper = this.bare ? null : new QName(namespace, wrapperName);
        if (this.bare || StringUtils.equals("rpc", this.style)) {
            schema.removeChild(wrapper);
        }
        String encoding = "";
        if (StringUtils.equals("encoded", this.use)) {
            encoding = version == SoapVersion.SOAP_11 //
                    ? "http://schemas.xmlsoap.org/soap/encoding/" : "http://www.w3.org/2003/05/soap-encoding";
        }
        return new SoapBody(bodyWrapper, parts, List.of(), this.use, encoding);
    }

    private void requireWrapper(Set<String> wrappers, String name) {
        if (!wrappers.add(name)) {
            throw new IllegalArgumentException("SOAP operation wrappers have the same name: " + name);
        }
    }

    private void buildWsdl(String alias, String namespace, Map<String, Document> schemaDocuments) {
        String bindingNamespace = this.definition.version().bindingNamespace();
        Element definitions = this.wsdl.createElementNS(WSDL_NAMESPACE, "wsdl:definitions");
        this.wsdl.appendChild(definitions);
        definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:tns", namespace);
        definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD_NAMESPACE);
        definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:soap", bindingNamespace);
        definitions.setAttribute("name", alias);
        definitions.setAttribute("targetNamespace", namespace);
        Element types = SoapXmlUtils.append(definitions, WSDL_NAMESPACE, "wsdl:types");
        for (Document schemaDocument : schemaDocuments.values()) {
            Element schema = (Element) this.wsdl.importNode(schemaDocument.getDocumentElement(), true);
            // 所有生成的 Schema 一同内嵌，导入按 namespace 关联；外部资源仍保留相对文件名。
            for (Node node = schema.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element imported && StringUtils.equals("import", imported.getLocalName())) {
                    // XML 标准属性引用随插件发布的原始 Schema，避免复制标准定义。
                    if (!StringUtils.equals("xml-namespace.xsd", imported.getAttribute("schemaLocation"))) {
                        imported.removeAttribute("schemaLocation");
                    }
                }
            }
            types.appendChild(schema);
        }

        // WSDL message、portType 和 binding 共用生成的 operation 描述。
        for (SoapOperation operation : this.definition.operations()) {
            this.message(definitions, operation.name() + "Request", operation.inputBody());
            if (!operation.oneWay()) {
                this.message(definitions, operation.name() + "Response", operation.outputBody());
            }
        }
        Element portType = SoapXmlUtils.append(definitions, WSDL_NAMESPACE, "wsdl:portType");
        portType.setAttribute("name", alias + "PortType");
        Element binding = SoapXmlUtils.append(definitions, WSDL_NAMESPACE, "wsdl:binding");
        binding.setAttribute("name", alias + "Binding");
        binding.setAttribute("type", "tns:" + alias + "PortType");
        Element soapBinding = SoapXmlUtils.append(binding, bindingNamespace, "soap:binding");
        soapBinding.setAttribute("style", this.style);
        soapBinding.setAttribute("transport", "http://schemas.xmlsoap.org/soap/http");
        for (SoapOperation operation : this.definition.operations()) {
            Element portOperation = SoapXmlUtils.append(portType, WSDL_NAMESPACE, "wsdl:operation");
            portOperation.setAttribute("name", operation.name());
            this.portMessage(portOperation, "input", operation.name() + "Request");
            if (!operation.oneWay()) {
                this.portMessage(portOperation, "output", operation.name() + "Response");
            }
            Element boundOperation = SoapXmlUtils.append(binding, WSDL_NAMESPACE, "wsdl:operation");
            boundOperation.setAttribute("name", operation.name());
            Element soapOperation = SoapXmlUtils.append(boundOperation, bindingNamespace, "soap:operation");
            soapOperation.setAttribute("soapAction", operation.soapAction());
            if (this.definition.version() == SoapVersion.SOAP_12) {
                soapOperation.setAttribute("soapActionRequired", Boolean.toString(operation.actionRequired()));
            }
            this.boundBody(boundOperation, "input", operation.inputBody());
            if (!operation.oneWay()) {
                this.boundBody(boundOperation, "output", operation.outputBody());
            }
        }
        Element service = SoapXmlUtils.append(definitions, WSDL_NAMESPACE, "wsdl:service");
        service.setAttribute("name", alias);
        Element port = SoapXmlUtils.append(service, WSDL_NAMESPACE, "wsdl:port");
        port.setAttribute("name", alias + "Port");
        port.setAttribute("binding", "tns:" + alias + "Binding");
        SoapXmlUtils.append(port, bindingNamespace, "soap:address");
    }

    private void message(Element definitions, String name, SoapBody body) {
        Element message = SoapXmlUtils.append(definitions, WSDL_NAMESPACE, "wsdl:message");
        message.setAttribute("name", name);
        if (StringUtils.equals("document", this.style) && !this.bare) {
            Element part = SoapXmlUtils.append(message, WSDL_NAMESPACE, "wsdl:part");
            part.setAttribute("name", "parameters");
            part.setAttribute("element", "tns:" + body.wrapper().getLocalPart());
            return;
        }
        for (SoapPart value : body.parts()) {
            Element part = SoapXmlUtils.append(message, WSDL_NAMESPACE, "wsdl:part");
            part.setAttribute("name", value.name());
            boolean encodedType = body.encoded() && this.definition.version() == SoapVersion.SOAP_11;
            if (this.bare && !encodedType) {
                part.setAttribute("element", this.qname(definitions, value.element()));
            } else {
                part.setAttribute("type", this.qname(definitions, value.type()));
            }
        }
    }

    private String qname(Element definitions, QName name) {
        String namespace = name.getNamespaceURI();
        if (namespace.isEmpty()) {
            return name.getLocalPart();
        }
        String prefix = definitions.lookupPrefix(namespace);
        if (prefix == null) {
            prefix = "ns" + definitions.getAttributes().getLength();
            definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace);
        }
        return prefix + ":" + name.getLocalPart();
    }

    private void portMessage(Element operation, String direction, String message) {
        Element content = SoapXmlUtils.append(operation, WSDL_NAMESPACE, "wsdl:" + direction);
        content.setAttribute("message", "tns:" + message);
    }

    private void boundBody(Element operation, String direction, SoapBody description) {
        Element content = SoapXmlUtils.append(operation, WSDL_NAMESPACE, "wsdl:" + direction);
        String bindingNamespace = this.definition.version().bindingNamespace();
        Element body = SoapXmlUtils.append(content, bindingNamespace, "soap:body");
        body.setAttribute("use", description.use());
        if (StringUtils.equals("rpc", this.style) || description.encoded()) {
            body.setAttribute("namespace", this.definition.targetNamespace());
        }
        boolean document12 = this.definition.version() == SoapVersion.SOAP_12 //
                && StringUtils.equals("document", this.style);
        if (!description.encodingStyle().isEmpty() && !document12) {
            body.setAttribute("encodingStyle", description.encodingStyle());
        }
    }

    private static byte[] serialize(Document document) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException failure) {
            throw new IOException("Unable to render generated SOAP definition", failure);
        }
    }
}
