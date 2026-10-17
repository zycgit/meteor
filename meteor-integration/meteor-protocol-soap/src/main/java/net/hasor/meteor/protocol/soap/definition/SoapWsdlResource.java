/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** WSDL 1.1 定义及其受限的同源 XSD/WSDL 资源。 */
public final class SoapWsdlResource extends SoapDefinitionResource {
    private static final int                          MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
    private static final int                          MAX_TOTAL_BYTES    = 8 * 1024 * 1024;
    private static final int                          MAX_RESOURCES      = 64;
    private static final int                          MAX_IMPORT_DEPTH   = 8;
    private final        URL                          wsdl;
    private final        String                       wsdlName;
    private final        Map<String, SoapXmlResource> resources;
    private final        SoapDefinition               definition;
    private final        SoapSchemaParser             schemas;

    public SoapWsdlResource(URL wsdl) throws IOException {
        if (wsdl == null) {
            throw new IllegalArgumentException("WSDL URL is required.");
        }

        this.wsdl = wsdl;
        this.wsdlName = this.entryName(wsdl);

        // 资源图由插件主动读取；XML 解析器本身禁止解析任何外部资源。
        LoadState state = new LoadState();
        this.load(this.wsdlName, wsdl, 0, state);
        this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(state.resources()));
        this.validateIncludes();

        SoapXmlResource entry = this.resources.get(this.wsdlName);
        this.schemas = new SoapSchemaParser(this.resources.values(), entry);
        this.definition = new SoapWsdlParser(this.resources.values(), entry, this.schemas, "auto").parse();
    }

    public URL getWsdl() {
        return this.wsdl;
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

    SoapDefinitionResource parameterStyle(String style) {
        if (StringUtils.equals("auto", style)) {
            return this;
        }
        if (!StringUtils.equals("wrapped", style) && !StringUtils.equals("bare", style)) {
            throw new IllegalArgumentException("SOAP soapParameterStyle must be auto, wrapped or bare: " + style);
        }
        try {
            SoapXmlResource entry = this.resources.get(this.wsdlName);
            SoapWsdlParser parser = new SoapWsdlParser(this.resources.values(), entry, this.schemas, style);
            SoapDefinition parsed = parser.parse();
            return new SelectedResource(this, parsed);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to select SOAP parameter style: " + style, failure);
        }
    }

    String wsdlName() {
        return this.wsdlName;
    }

    Set<String> resourceNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.resources.keySet()));
    }

    byte[] resource(String name) {
        SoapXmlResource resource = this.resources.get(name);
        return resource == null ? null : resource.body();
    }

    @Override
    public byte[] renderWsdl(URI endpoint) throws IOException {
        return this.render(this.resources.get(this.wsdlName), endpoint);
    }

    @Override
    public byte[] renderResource(String name, URI endpoint) throws IOException {
        SoapXmlResource resource = this.resources.get(name);
        return resource == null ? null : this.render(resource, endpoint);
    }

    private void load(String name, URL source, int depth, LoadState state) throws IOException {
        if (state.resources().containsKey(name)) {
            return;
        }
        if (depth > MAX_IMPORT_DEPTH) {
            throw new IOException("WSDL/XSD import depth exceeds " + MAX_IMPORT_DEPTH + ": " + name);
        }
        if (state.resources().size() >= MAX_RESOURCES) {
            throw new IOException("WSDL/XSD resource count exceeds " + MAX_RESOURCES + ".");
        }
        this.requireSameOrigin(source);

        // 每个资源和整个定义图均受限制，避免发布服务时占用无界内存。
        byte[] body = this.read(source);
        state.addBytes(body.length);
        Document document = this.parse(source, body);
        SoapXmlResource resource = new SoapXmlResource(name, source, body, document);
        state.resources().put(name, resource);

        for (String location : this.importLocations(document)) {
            URL dependency;
            String dependencyName;
            if (this.httpSource()) {
                dependency = this.resolveHttpSource(source, location);
                dependencyName = this.httpResourceName(dependency);
            } else {
                dependencyName = this.resolveName(name, location);
                dependency = new URL(source, location);
            }
            this.load(dependencyName, dependency, depth + 1, state);
        }
    }

    private byte[] read(URL source) throws IOException {
        URLConnection connection = source.openConnection();
        connection.setUseCaches(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(false);
            int status = http.getResponseCode();
            if (status >= 300 && status < 400) {
                http.disconnect();
                throw new IOException("WSDL/XSD resource redirects are not allowed: " + source);
            }
        }

        try (InputStream input = connection.getInputStream()) {
            byte[] body = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (body.length > MAX_RESOURCE_BYTES) {
                throw new IOException("WSDL/XSD resource exceeds 2 MiB: " + source);
            }
            return body;
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    private Document parse(URL source, byte[] body) throws IOException {
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
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            builder.setErrorHandler(new ThrowingErrorHandler());
            Document document = builder.parse(new ByteArrayInputStream(body), source.toExternalForm());
            try {
                SoapXmlUtils.validateDocument(document, "WSDL/XSD resource");
            } catch (IllegalArgumentException failure) {
                throw new IOException(failure.getMessage() + ": " + source, failure);
            }
            return document;
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("Invalid WSDL/XSD resource: " + source, failure);
        }
    }

    private List<String> importLocations(Document document) throws IOException {
        NodeList redefines = document.getElementsByTagNameNS(SoapWsdlParser.XSD_NAMESPACE, "redefine");
        if (redefines.getLength() > 0) {
            throw new IOException("XML Schema redefine is not supported.");
        }

        List<String> locations = new ArrayList<>();
        this.addLocations(document, SoapWsdlParser.WSDL_NAMESPACE, "import", "location", false, locations);
        this.addLocations(document, SoapWsdlParser.XSD_NAMESPACE, "import", "schemaLocation", false, locations);
        this.addLocations(document, SoapWsdlParser.XSD_NAMESPACE, "include", "schemaLocation", true, locations);
        return locations;
    }

    private void validateIncludes() throws IOException {
        for (SoapXmlResource resource : this.resources.values()) {
            NodeList includes = resource.document().getElementsByTagNameNS(SoapWsdlParser.XSD_NAMESPACE, //
                    "include");
            for (int index = 0; index < includes.getLength(); index++) {
                Element include = (Element) includes.item(index);
                String location = include.getAttribute("schemaLocation");
                String includedName = this.resolveName(resource.name(), location);
                SoapXmlResource included = this.resources.get(includedName);
                if (included == null) {
                    throw new IOException("XML Schema include was not loaded: " + includedName);
                }

                Element includedSchema = included.document().getDocumentElement();
                boolean schemaNamespace = StringUtils.equals(SoapWsdlParser.XSD_NAMESPACE, //
                        includedSchema.getNamespaceURI());
                boolean schemaDocument = schemaNamespace && StringUtils.equals("schema", includedSchema.getLocalName());
                if (!schemaDocument) {
                    throw new IOException("XML Schema include does not identify a schema document: " + includedName);
                }

                Element ownerSchema = include;
                while (ownerSchema != null && !StringUtils.equals("schema", ownerSchema.getLocalName())) {
                    Node parent = ownerSchema.getParentNode();
                    ownerSchema = parent instanceof Element ? (Element) parent : null;
                }
                if (ownerSchema == null) {
                    throw new IOException("XML Schema include is outside a schema document: " + includedName);
                }

                String ownerNamespace = ownerSchema.getAttribute("targetNamespace");
                String includedNamespace = includedSchema.getAttribute("targetNamespace");
                boolean chameleon = includedNamespace.isBlank();
                if (!chameleon && !StringUtils.equals(ownerNamespace, includedNamespace)) {
                    String message = "Included XML Schema targetNamespace differs from its owner: ";
                    throw new IOException(message + includedName);
                }
            }
        }
    }

    private void addLocations(Document document, String namespace, String elementName, String attributeName, //
            boolean required, List<String> locations) throws IOException {
        NodeList nodes = document.getElementsByTagNameNS(namespace, elementName);
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String location = element.getAttribute(attributeName);
            if (location.isBlank()) {
                if (required) {
                    throw new IOException(elementName + " requires " + attributeName + ".");
                }
                continue;
            }
            if (!this.httpSource()) {
                this.validateRelativeLocation(location);
            }
            locations.add(location);
        }
    }

    private byte[] render(SoapXmlResource resource, URI endpoint) throws IOException {
        if (resource == null) {
            return null;
        }

        URI endpointUri = this.endpoint(endpoint);
        Document document = (Document) resource.document().cloneNode(true);

        // 定义页面指向当前实际服务端点；引用统一回到同一个协议挂载点。
        String bindingNamespace = this.definition.version().bindingNamespace();
        NodeList addresses = document.getElementsByTagNameNS(bindingNamespace, "address");
        for (int index = 0; index < addresses.getLength(); index++) {
            Element address = (Element) addresses.item(index);
            address.setAttribute("location", endpointUri.toASCIIString());
        }
        this.rewriteLocations(document, resource.name(), endpointUri, SoapWsdlParser.WSDL_NAMESPACE, //
                "import", "location", "wsdl");
        this.rewriteLocations(document, resource.name(), endpointUri, SoapWsdlParser.XSD_NAMESPACE, //
                "import", "schemaLocation", "xsd");
        this.rewriteLocations(document, resource.name(), endpointUri, SoapWsdlParser.XSD_NAMESPACE, //
                "include", "schemaLocation", "xsd");

        return this.serialize(document);
    }

    private void rewriteLocations(Document document, String currentName, URI endpoint, String namespace, //
            String elementName, String attributeName, String parameterName) throws IOException {
        NodeList nodes = document.getElementsByTagNameNS(namespace, elementName);
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String location = element.getAttribute(attributeName);
            if (location.isBlank()) {
                continue;
            }

            String resourceName = this.resolveName(currentName, location);
            if (!this.resources.containsKey(resourceName)) {
                throw new IOException("WSDL/XSD import was not loaded: " + resourceName);
            }
            String encoded = URLEncoder.encode(resourceName, StandardCharsets.UTF_8).replace("+", "%20");
            element.setAttribute(attributeName, endpoint.toASCIIString() + "?" + parameterName + "=" + encoded);
        }
    }

    private byte[] serialize(Document document) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "no");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException failure) {
            throw new IOException("Unable to render WSDL/XSD resource.", failure);
        }
    }

    private URI endpoint(URI endpoint) throws IOException {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.isOpaque()) {
            throw new IOException("SOAP endpoint must be an absolute hierarchical URI.");
        }
        // 保留已经转义的路径，只移除查询与片段，避免将 % 再编码成 %25。
        String address = endpoint.toASCIIString();
        int fragment = address.indexOf('#');
        if (fragment >= 0) {
            address = address.substring(0, fragment);
        }
        int query = address.indexOf('?');
        if (query >= 0) {
            address = address.substring(0, query);
        }
        return URI.create(address);
    }

    private String entryName(URL source) throws IOException {
        String path = source.getPath();
        int separator = path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        if (name.isBlank()) {
            throw new IOException("WSDL URL must identify a file: " + source);
        }
        String query = source.getQuery();
        return this.httpSource() && query != null ? name + "?" + query : name;
    }

    private String resolveName(String currentName, String location) throws IOException {
        if (this.httpSource()) {
            URL current = new URL(this.wsdl, currentName);
            return this.httpResourceName(this.resolveHttpSource(current, location));
        }
        this.validateRelativeLocation(location);
        try {
            URI base = new URI(null, null, "/" + currentName, null);
            String path = base.resolve(location).normalize().getPath();
            if (path == null || !path.startsWith("/") || path.startsWith("/../")) {
                throw new IOException("WSDL/XSD import escapes its resource root: " + location);
            }

            String name = path.substring(1);
            if (name.isBlank() || name.startsWith("../")) {
                throw new IOException("WSDL/XSD import escapes its resource root: " + location);
            }
            return name;
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid WSDL/XSD import location: " + location, failure);
        }
    }

    private boolean httpSource() {
        String protocol = this.wsdl.getProtocol();
        return StringUtils.equalsIgnoreCase("http", protocol) || StringUtils.equalsIgnoreCase("https", protocol);
    }

    private URL resolveHttpSource(URL current, String location) throws IOException {
        try {
            URI reference = new URI(location);
            String path = reference.getPath();
            boolean invalid = reference.getRawFragment() != null || reference.getRawUserInfo() != null || path == null;
            if (invalid) {
                throw new IOException("Invalid same-origin WSDL/XSD import: " + location);
            }
            this.requireResourcePath(path, location);

            // JDK 的 query-only resolve 会丢掉文件名；资源查询必须保留当前端点。
            URI source = current.toURI();
            URI resolved;
            boolean queryOnly = !reference.isAbsolute() && reference.getRawAuthority() == null && path.isEmpty();
            if (queryOnly && reference.getRawQuery() != null) {
                String endpoint = source.toASCIIString();
                int separator = endpoint.indexOf('?');
                endpoint = separator < 0 ? endpoint : endpoint.substring(0, separator);
                resolved = new URI(endpoint + "?" + reference.getRawQuery());
            } else {
                resolved = source.resolve(reference).normalize();
            }
            URL dependency = resolved.toURL();
            this.requireSameOrigin(dependency);
            return dependency;
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("Invalid WSDL/XSD import location: " + location, failure);
        }
    }

    private String httpResourceName(URL source) throws IOException {
        String entryPath = this.wsdl.getPath();
        String directory = entryPath.substring(0, entryPath.lastIndexOf('/') + 1);
        String path = source.getPath();
        if (!path.startsWith(directory)) {
            throw new IOException("WSDL/XSD import escapes its resource root: " + source);
        }
        String name = path.substring(directory.length());
        if (name.isBlank()) {
            throw new IOException("WSDL/XSD import must identify a resource: " + source);
        }
        String query = source.getQuery();
        return query == null ? name : name + "?" + query;
    }

    private void requireResourcePath(String path, String location) throws IOException {
        if (path.indexOf('\\') >= 0) {
            throw new IOException("WSDL/XSD import must use forward slashes: " + location);
        }
        for (String segment : path.split("/")) {
            if (StringUtils.equals("..", segment)) {
                throw new IOException("WSDL/XSD import escapes its resource root: " + location);
            }
        }
    }

    private void validateRelativeLocation(String location) throws IOException {
        try {
            URI reference = new URI(location);
            String path = reference.getPath();
            if (reference.isAbsolute() || reference.getRawAuthority() != null || reference.getRawQuery() != null || //
                    reference.getRawFragment() != null || path == null || path.startsWith("/")) {
                throw new IOException("WSDL/XSD import must use a relative same-origin URL: " + location);
            }
            this.requireResourcePath(path, location);
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid WSDL/XSD import location: " + location, failure);
        }
    }

    private void requireSameOrigin(URL source) throws IOException {
        boolean protocol = StringUtils.equalsIgnoreCase(this.wsdl.getProtocol(), source.getProtocol());
        boolean host = StringUtils.equalsIgnoreCase(this.wsdl.getHost(), source.getHost());
        boolean port = this.effectivePort(this.wsdl) == this.effectivePort(source);
        if (!protocol || !host || !port) {
            throw new IOException("WSDL/XSD import crosses its source origin: " + source);
        }
    }

    private int effectivePort(URL source) {
        int port = source.getPort();
        return port < 0 ? source.getDefaultPort() : port;
    }

    /** 同一资源图可以针对端点配置选择 wrapped/bare Java 映射，原始 WSDL 内容不变。 */
    private static final class SelectedResource extends SoapDefinitionResource {
        private final SoapWsdlResource source;
        private final SoapDefinition   definition;

        private SelectedResource(SoapWsdlResource source, SoapDefinition definition) {
            this.source = source;
            this.definition = definition;
        }

        @Override
        public SoapDefinition definition() {
            return this.definition;
        }

        @Override
        public void validateBody(Element body, SoapBody binding) throws IOException {
            this.source.validateBody(body, binding);
        }

        @Override
        public void validatePart(Element payload, SoapPart part) throws IOException {
            this.source.validatePart(payload, part);
        }

        @Override
        public byte[] renderWsdl(URI endpoint) throws IOException {
            return this.source.renderWsdl(endpoint);
        }

        @Override
        public byte[] renderResource(String name, URI endpoint) throws IOException {
            return this.source.renderResource(name, endpoint);
        }
    }

    /** 构造阶段使用的有界可变状态。 */
    private static final class LoadState {
        private final Map<String, SoapXmlResource> resources;
        private       int                          totalBytes;

        private LoadState() {
            this.resources = new LinkedHashMap<>();
        }

        private Map<String, SoapXmlResource> resources() {
            return this.resources;
        }

        private void addBytes(int bytes) throws IOException {
            this.totalBytes += bytes;
            if (this.totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("WSDL/XSD resources exceed 8 MiB in total.");
            }
        }
    }

    /** 禁止解析器将语法错误写到标准错误流。 */
    private static final class ThrowingErrorHandler extends DefaultHandler {
        private ThrowingErrorHandler() {
        }

        @Override
        public void warning(SAXParseException failure) throws SAXException {
            throw failure;
        }

        @Override
        public void error(SAXParseException failure) throws SAXException {
            throw failure;
        }

        @Override
        public void fatalError(SAXParseException failure) throws SAXException {
            throw failure;
        }
    }
}
