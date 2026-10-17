/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import jakarta.activation.DataSource;
import jakarta.mail.BodyPart;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** SOAP HTTP 实体与本地 MIME 附件之间的转换，不解析业务类型、不访问外部 URI。 */
final class SoapAttachmentCodec {
    private static final String XOP              = "http://www.w3.org/2004/08/xop/include";
    private static final String XOP_TYPE         = "application/xop+xml";
    private static final int    MAX_PARTS        = 256;
    private static final int    MAX_HEADER_BYTES = 16 * 1024;

    private SoapAttachmentCodec() {
    }

    static Message encode(Document envelope, Map<Element, byte[]> binaries, SoapVersion version, //
            String action, boolean xop, int maxBytes) throws Exception {
        requireLimit(maxBytes);
        SoapXmlUtils.validateDocument(envelope, "SOAP attachment message");
        ContentType soapType = soapType(version, action);
        if (binaries.isEmpty()) {
            return new Message(xml(envelope, maxBytes), header(soapType));
        }
        if (binaries.size() >= MAX_PARTS) {
            throw new IllegalArgumentException("SOAP attachment message contains too many MIME parts");
        }
        if (envelope.getElementsByTagNameNS(XOP, "Include").getLength() != 0) {
            throw new IllegalArgumentException("SOAP source envelope already contains xop:Include");
        }

        // 在副本中替换已由业务类型明确标记的二进制元素，普通字符串不参与优化。
        Document copy = (Document) envelope.cloneNode(true);
        Map<Element, Element> copies = elementCopies(envelope, copy);
        MimeMultipart multipart = new MimeMultipart("related");
        String rootId = UUID.randomUUID() + "@meteor-root";
        List<MimeBodyPart> attachments = new ArrayList<>();
        long binaryBytes = 0;
        for (Map.Entry<Element, byte[]> entry : binaries.entrySet()) {
            Element target = copies.get(entry.getKey());
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "binary value");
            if (target == null) {
                throw new IllegalArgumentException("SOAP binary element must belong to the envelope");
            }
            binaryBytes += bytes.length;
            requireSize(binaryBytes, maxBytes);
            requireBinaryContent(target, bytes);
            String id = UUID.randomUUID() + "@meteor-attachment";
            target.setTextContent("");
            if (xop) {
                Element include = copy.createElementNS(XOP, "xop:Include");
                include.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xop", XOP);
                include.setAttribute("href", "cid:" + id);
                target.appendChild(include);
            } else {
                target.setAttribute("href", "cid:" + id);
            }
            attachments.add(part(bytes, "application/octet-stream", id));
        }

        // root type 和 start-info 都携带原 SOAP 媒体类型，保留 SOAP 1.2 action。
        ContentType rootType = xop ? new ContentType(XOP_TYPE) : soapType;
        if (xop) {
            rootType.setParameter("type", header(soapType));
            rootType.setParameter("charset", StandardCharsets.UTF_8.name());
        }
        multipart.addBodyPart(part(xml(copy, maxBytes), header(rootType), rootId));
        for (MimeBodyPart attachment : attachments) {
            multipart.addBodyPart(attachment);
        }
        ContentType packageType = new ContentType(multipart.getContentType());
        packageType.setParameter("type", xop ? XOP_TYPE : version.contentType());
        packageType.setParameter("start", "<" + rootId + ">");
        if (xop) {
            packageType.setParameter("start-info", header(soapType));
        }
        LimitedOutput output = new LimitedOutput(maxBytes);
        multipart.writeTo(output);
        return new Message(output.bytes(), header(packageType));
    }

    static Message decode(byte[] body, String contentType, SoapVersion version, int maxBytes) throws Exception {
        requireLimit(maxBytes);
        requireSize(body.length, maxBytes);
        ContentType packageType = contentType(contentType);
        if (!StringUtils.equalsIgnoreCase(packageType.getBaseType(), "multipart/related")) {
            return new Message(body, contentType);
        }
        validateBoundary(packageType.getParameter("boundary"));
        String expectedRootType = packageType.getParameter("type");
        if (StringUtils.isBlank(expectedRootType)) {
            throw new IllegalArgumentException("SOAP multipart/related requires a type parameter");
        }

        // 标准 MIME 解析器负责分界线、折行及传输编码；严格模式拒绝截断消息。
        StrictMultipart multipart = new StrictMultipart(new ByteArrayDataSource(body, contentType));
        List<Part> parts = new ArrayList<>();
        Map<String, Part> ids = new HashMap<>();
        long decodedBytes = 0;
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart source = multipart.getBodyPart(index);
            validateHeaders(source);
            String idHeader = singleHeader(source, "Content-ID");
            String id = idHeader == null ? null : contentId(idHeader);
            String partType = singleHeader(source, "Content-Type");
            if (partType == null) {
                partType = "text/plain";
            }
            contentType(partType);
            String encoding = singleHeader(source, "Content-Transfer-Encoding");
            validateEncoding(encoding);
            byte[] bytes;
            try (InputStream input = source.getInputStream()) {
                bytes = input.readNBytes(maxBytes + 1);
            }
            decodedBytes += bytes.length;
            requireSize(decodedBytes, maxBytes);
            Part part = new Part(bytes, partType, singleHeader(source, "Content-Location"), encoding);
            parts.add(part);
            if (id != null && ids.putIfAbsent(id, part) != null) {
                throw new IllegalArgumentException("Duplicate SOAP attachment Content-ID: " + id);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("SOAP MIME package has no root part");
        }
        String start = packageType.getParameter("start");
        Part root = start == null ? parts.get(0) : ids.get(contentId(start));
        if (root == null) {
            throw new IllegalArgumentException("SOAP MIME start does not identify a local root part");
        }
        ContentType rootType = contentType(root.contentType);
        ContentType expected = contentType(expectedRootType);
        if (!StringUtils.equalsIgnoreCase(expected.getBaseType(), rootType.getBaseType())) {
            throw new IllegalArgumentException("SOAP MIME root does not match the package type");
        }
        boolean xop = StringUtils.equalsIgnoreCase(rootType.getBaseType(), XOP_TYPE);
        ContentType soapType = resolveSoapType(packageType, rootType, version, xop);
        String charset = rootType.getParameter("charset");
        if (charset == null) {
            charset = soapType.getParameter("charset");
        }
        Document document = parse(root.body, charset);
        URI packageBase = URI.create("thismessage:/");
        URI base = root.location == null ? packageBase : location(root.location, packageBase);
        document.setDocumentURI(base.toString());
        Map<URI, Part> locations = locations(parts, packageBase);
        restore(document, root, ids, locations, xop, maxBytes);
        soapType.setParameter("charset", StandardCharsets.UTF_8.name());
        return new Message(xml(document, maxBytes), header(soapType));
    }

    private static ContentType resolveSoapType(ContentType packageType, ContentType rootType, //
            SoapVersion version, boolean xop) throws Exception {
        ContentType soapType = rootType;
        if (xop) {
            String nested = rootType.getParameter("type");
            String startInfo = packageType.getParameter("start-info");
            if (StringUtils.isBlank(nested) || StringUtils.isBlank(startInfo)) {
                throw new IllegalArgumentException("MTOM requires root type and package start-info");
            }
            soapType = contentType(nested);
            ContentType info = contentType(startInfo);
            if (!StringUtils.equalsIgnoreCase(soapType.getBaseType(), info.getBaseType())) {
                throw new IllegalArgumentException("MTOM start-info does not match the root SOAP type");
            }
            String rootAction = soapType.getParameter("action");
            String infoAction = info.getParameter("action");
            String xopAction = rootType.getParameter("action");
            String action = mergeAction(rootAction, infoAction);
            action = mergeAction(action, xopAction);
            if (action != null) {
                soapType.setParameter("action", action);
            }
        }
        if (!StringUtils.equalsIgnoreCase(soapType.getBaseType(), version.contentType())) {
            String message = "SOAP attachment media type does not match SOAP " + version.displayName();
            throw new IllegalArgumentException(message);
        }
        return soapType;
    }

    private static String mergeAction(String first, String second) {
        if (first != null && second != null && !StringUtils.equals(first, second)) {
            throw new IllegalArgumentException("Conflicting SOAP action parameters in MIME package");
        }
        return first == null ? second : first;
    }

    private static void restore(Document document, Part root, Map<String, Part> ids, Map<URI, Part> locations, //
            boolean xop, int maxBytes) {
        Queue<Element> remaining = new ArrayDeque<>();
        Set<Part> referenced = Collections.newSetFromMap(new IdentityHashMap<>());
        remaining.add(document.getDocumentElement());
        long expanded = 0;
        while (!remaining.isEmpty()) {
            Element element = remaining.remove();
            boolean include = StringUtils.equals(XOP, element.getNamespaceURI()) //
                    && StringUtils.equals("Include", element.getLocalName());
            String href = element.hasAttribute("href") ? element.getAttribute("href") : null;
            if (include) {
                if (!xop || href == null || !(element.getParentNode() instanceof Element parent)) {
                    throw new IllegalArgumentException("Invalid SOAP xop:Include");
                }
                if (parent.getChildNodes().getLength() != 1) {
                    throw new IllegalArgumentException("xop:Include must be the sole content of its parent");
                }
                Part attachment = resolve(href, element, ids, locations, true);
                if (attachment == root || !referenced.add(attachment)) {
                    throw new IllegalArgumentException("MTOM binary part must be referenced exactly once");
                }
                if (attachment.encoding == null) {
                    throw new IllegalArgumentException("MTOM binary part requires Content-Transfer-Encoding");
                }
                expanded += 4L * ((attachment.body.length + 2L) / 3);
                requireSize(expanded, maxBytes);
                parent.setTextContent(Base64.getEncoder().encodeToString(attachment.body));
                continue;
            }
            if (!xop && href != null && !href.startsWith("#")) {
                if (element.hasChildNodes()) {
                    throw new IllegalArgumentException("SOAP attachment reference must not contain inline content");
                }
                Part attachment = resolve(href, element, ids, locations, false);
                if (attachment == root) {
                    throw new IllegalArgumentException("SOAP attachment must not reference its root part");
                }
                expanded += 4L * ((attachment.body.length + 2L) / 3);
                requireSize(expanded, maxBytes);
                element.removeAttribute("href");
                element.setTextContent(Base64.getEncoder().encodeToString(attachment.body));
                continue;
            }
            NodeList children = element.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                if (children.item(index) instanceof Element child) {
                    remaining.add(child);
                }
            }
        }
    }

    private static Part resolve(String href, Element element, Map<String, Part> ids, //
            Map<URI, Part> locations, boolean cidOnly) {
        URI uri = URI.create(href);
        Part result;
        if (StringUtils.equalsIgnoreCase(uri.getScheme(), "cid") && uri.getRawFragment() == null) {
            result = ids.get(uri.getSchemeSpecificPart());
        } else if (!cidOnly && uri.getRawFragment() == null) {
            URI base = URI.create(element.getBaseURI());
            result = locations.get(location(href, base));
        } else {
            throw new IllegalArgumentException("SOAP attachment href must identify a local MIME part: " + href);
        }
        if (result == null) {
            throw new IllegalArgumentException("SOAP attachment href has no local MIME part: " + href);
        }
        return result;
    }

    private static Map<URI, Part> locations(List<Part> parts, URI base) {
        Map<URI, Part> locations = new HashMap<>();
        for (Part part : parts) {
            if (part.location != null) {
                URI uri = location(part.location, base);
                if (locations.putIfAbsent(uri, part) != null) {
                    throw new IllegalArgumentException("Duplicate SOAP attachment Content-Location: " + uri);
                }
            }
        }
        return locations;
    }

    private static URI location(String value, URI base) {
        URI uri = URI.create(value);
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("SOAP attachment location must not contain a fragment");
        }
        return base.resolve(uri).normalize();
    }

    private static Map<Element, Element> elementCopies(Document original, Document copy) {
        Map<Element, Element> result = new IdentityHashMap<>();
        Queue<Node[]> remaining = new ArrayDeque<>();

        // 每对节点固定为 [0] 原节点、[1] 副本，出队时按此位置建立对应关系。
        remaining.add(new Node[] { original, copy });
        while (!remaining.isEmpty()) {
            Node[] pair = remaining.remove();
            if (pair[0] instanceof Element first && pair[1] instanceof Element second) {
                result.put(first, second);
            }
            NodeList firstChildren = pair[0].getChildNodes();
            NodeList secondChildren = pair[1].getChildNodes();
            for (int index = 0; index < firstChildren.getLength(); index++) {
                // 同一索引的子节点也按原节点、副本顺序入队，保持两棵树逐节点对齐。
                remaining.add(new Node[] { firstChildren.item(index), secondChildren.item(index) });
            }
        }
        return result;
    }

    private static void requireBinaryContent(Element element, byte[] bytes) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            short type = children.item(index).getNodeType();
            if (type != Node.TEXT_NODE && type != Node.CDATA_SECTION_NODE) {
                throw new IllegalArgumentException("SOAP binary element must contain only canonical base64 text");
            }
        }
        String expected = Base64.getEncoder().encodeToString(bytes);
        if (!StringUtils.equals(expected, element.getTextContent())) {
            throw new IllegalArgumentException("SOAP binary value does not match its canonical XML base64 text");
        }
    }

    private static MimeBodyPart part(byte[] body, String contentType, String id) throws MessagingException {
        InternetHeaders headers = new InternetHeaders();
        headers.setHeader("Content-Type", contentType);
        headers.setHeader("Content-ID", "<" + id + ">");
        headers.setHeader("Content-Transfer-Encoding", "binary");
        return new MimeBodyPart(headers, body);
    }

    private static ContentType soapType(SoapVersion version, String action) throws MessagingException {
        ContentType contentType = new ContentType(version.contentType());
        contentType.setParameter("charset", StandardCharsets.UTF_8.name());
        if (version == SoapVersion.SOAP_12 && action != null) {
            for (int index = 0; index < action.length(); index++) {
                char character = action.charAt(index);
                if (character < 32 || character == 127) {
                    throw new IllegalArgumentException("SOAP action must not contain control characters");
                }
            }
            contentType.setParameter("action", action);
        }
        return contentType;
    }

    private static ContentType contentType(String value) throws MessagingException {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Missing SOAP MIME Content-Type");
        }
        ContentType result = new ContentType(value);
        HeaderTokenizer tokens = new HeaderTokenizer(value, HeaderTokenizer.MIME);
        tokens.next();
        tokens.next();
        tokens.next();
        Map<String, Set<Integer>> parameters = new HashMap<>();
        while (tokens.next().getType() != HeaderTokenizer.Token.EOF) {
            HeaderTokenizer.Token parameter = tokens.next();
            if (parameter.getType() != HeaderTokenizer.Token.ATOM) {
                throw new IllegalArgumentException("Malformed SOAP MIME Content-Type parameter");
            }
            String name = parameter.getValue().toLowerCase(Locale.ROOT);
            int star = name.indexOf('*');
            String base = star < 0 ? name : name.substring(0, star);
            int section = parameterSection(name, star);
            Set<Integer> sections = parameters.computeIfAbsent(base, key -> new HashSet<>());
            if (!sections.add(section) || sections.contains(-1) && sections.size() != 1) {
                throw new IllegalArgumentException("Ambiguous SOAP MIME Content-Type parameter: " + name);
            }
            tokens.next();
            tokens.next();
        }
        // RFC 2231 分段顺序不限，但必须从 0 开始连续；值的拼接和字符集解码仍由 MIME 库完成。
        for (Map.Entry<String, Set<Integer>> parameter : parameters.entrySet()) {
            Set<Integer> sections = parameter.getValue();
            if (sections.contains(-1)) {
                continue;
            }
            for (int section = 0; section < sections.size(); section++) {
                if (!sections.contains(section)) {
                    String message = "Invalid SOAP MIME Content-Type parameter continuation: " + parameter.getKey();
                    throw new IllegalArgumentException(message);
                }
            }
        }
        return result;
    }

    private static int parameterSection(String name, int star) {
        if (star < 0) {
            return -1;
        }
        if (star == 0) {
            throw new IllegalArgumentException("Invalid SOAP MIME Content-Type parameter: " + name);
        }
        String section = name.substring(star + 1);
        if (section.isEmpty()) {
            return -1;
        }
        if (section.endsWith("*")) {
            section = section.substring(0, section.length() - 1);
        }
        if (section.isEmpty() || section.length() > 1 && section.charAt(0) == '0') {
            throw new IllegalArgumentException("Invalid SOAP MIME Content-Type parameter continuation: " + name);
        }
        for (int index = 0; index < section.length(); index++) {
            char digit = section.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("Invalid SOAP MIME Content-Type parameter continuation: " + name);
            }
        }
        try {
            return Integer.parseInt(section);
        } catch (NumberFormatException failure) {
            String message = "Invalid SOAP MIME Content-Type parameter continuation: " + name;
            throw new IllegalArgumentException(message, failure);
        }
    }

    private static String header(ContentType type) {
        // HTTP 头不受邮件行长约束；保留完整参数，避免把还原后的 SOAP action 再次拆成续段。
        StringBuilder result = new StringBuilder(type.getBaseType());
        if (type.getParameterList() != null) {
            Enumeration<String> parameters = type.getParameterList().getNames();
            while (parameters.hasMoreElements()) {
                String name = parameters.nextElement();
                String value = type.getParameter(name);
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("SOAP MIME Content-Type parameter must not contain line breaks");
                }
                result.append("; ").append(name).append('=').append(MimeUtility.quote(value, HeaderTokenizer.MIME));
            }
        }
        return result.toString();
    }

    private static void validateBoundary(String boundary) {
        if (boundary == null || boundary.isEmpty() || boundary.length() > 70 || boundary.endsWith(" ")) {
            throw new IllegalArgumentException("Invalid SOAP MIME boundary");
        }
        for (int index = 0; index < boundary.length(); index++) {
            char value = boundary.charAt(index);
            boolean letter = value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
            boolean digit = value >= '0' && value <= '9';
            if (!letter && !digit && "'()+_,-./:=? ".indexOf(value) < 0) {
                throw new IllegalArgumentException("Invalid SOAP MIME boundary");
            }
        }
    }

    private static String contentId(String value) {
        String id = value.trim();
        if (id.length() < 3 || !id.startsWith("<") || !id.endsWith(">")) {
            throw new IllegalArgumentException("SOAP MIME Content-ID must use angle brackets");
        }
        id = id.substring(1, id.length() - 1);
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (character <= 32 || character >= 127 || character == '<' || character == '>') {
                throw new IllegalArgumentException("Invalid SOAP MIME Content-ID");
            }
        }
        return id;
    }

    private static void validateEncoding(String encoding) {
        if (encoding == null) {
            return;
        }
        Set<String> supported = Set.of("binary", "8bit", "7bit", "base64", "quoted-printable");
        if (!supported.contains(encoding.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported SOAP MIME Content-Transfer-Encoding: " + encoding);
        }
    }

    private static void validateHeaders(BodyPart part) throws MessagingException {
        int length = 0;
        Enumeration<Header> headers = part.getAllHeaders();
        while (headers.hasMoreElements()) {
            Header header = headers.nextElement();
            length += header.getName().length() + header.getValue().length() + 4;
            if (length > MAX_HEADER_BYTES) {
                throw new IllegalArgumentException("SOAP MIME part headers exceed maximum size");
            }
        }
    }

    private static String singleHeader(BodyPart part, String name) throws MessagingException {
        String[] values = part.getHeader(name);
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length != 1) {
            throw new IllegalArgumentException("SOAP MIME " + name + " must occur exactly once");
        }
        return MimeUtility.unfold(values[0]).trim();
    }

    private static Document parse(byte[] body, String charset) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        InputSource input = new InputSource(new ByteArrayInputStream(body));
        if (charset != null) {
            input.setEncoding(charset);
        }
        Document document = factory.newDocumentBuilder().parse(input);
        SoapXmlUtils.validateDocument(document, "SOAP attachment message");
        return document;
    }

    private static byte[] xml(Document document, int maxBytes) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        LimitedOutput output = new LimitedOutput(maxBytes);
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.bytes();
    }

    private static void requireLimit(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            String message = "SOAP attachment maximum size must be positive and below Integer.MAX_VALUE";
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireSize(long size, int maxBytes) {
        if (size > maxBytes) {
            throw new IllegalArgumentException("SOAP attachment message exceeds maximum size " + maxBytes);
        }
    }

    static final class Message {
        private final byte[] body;
        private final String contentType;

        Message(byte[] body, String contentType) {
            this.body = body.clone();
            this.contentType = contentType;
        }

        public byte[] body() {
            return this.body.clone();
        }

        public String contentType() {
            return this.contentType;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Message that)) {
                return false;
            }
            return this.body == that.body && StringUtils.equals(this.contentType, that.contentType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.body, this.contentType);
        }

        @Override
        public String toString() {
            return "Message[body=" + this.body + ", contentType=" + this.contentType + "]";
        }
    }

    private static final class Part {
        private final byte[] body;
        private final String contentType;
        private final String location;
        private final String encoding;

        private Part(byte[] body, String contentType, String location, String encoding) {
            this.body = body;
            this.contentType = contentType;
            this.location = location;
            this.encoding = encoding;
        }

        public byte[] body() {
            return this.body;
        }

        public String contentType() {
            return this.contentType;
        }

        public String location() {
            return this.location;
        }

        public String encoding() {
            return this.encoding;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Part that)) {
                return false;
            }
            return this.body == that.body && //
                    StringUtils.equals(this.contentType, that.contentType) && //
                    StringUtils.equals(this.location, that.location) && //
                    StringUtils.equals(this.encoding, that.encoding);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.body, this.contentType, this.location, this.encoding);
        }

        @Override
        public String toString() {
            return "Part[body=" + this.body + ", contentType=" + this.contentType + ", location=" + this.location + //
                    ", encoding=" + this.encoding + "]";
        }
    }

    private static final class StrictMultipart extends MimeMultipart {
        private int count;

        private StrictMultipart(DataSource source) throws MessagingException {
            super(source);
        }

        @Override
        protected void initializeProperties() {
            this.ignoreMissingEndBoundary = false;
            this.ignoreMissingBoundaryParameter = false;
            this.ignoreExistingBoundaryParameter = false;
            this.allowEmpty = false;
        }

        @Override
        protected MimeBodyPart createMimeBodyPart(InternetHeaders headers, byte[] content) throws MessagingException {
            this.requirePart();
            return super.createMimeBodyPart(headers, content);
        }

        @Override
        protected MimeBodyPart createMimeBodyPart(InputStream input) throws MessagingException {
            this.requirePart();
            return super.createMimeBodyPart(input);
        }

        private void requirePart() {
            if (++this.count > MAX_PARTS) {
                throw new IllegalArgumentException("SOAP attachment message contains too many MIME parts");
            }
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int                   maximum;

        private LimitedOutput(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value) {
            requireSize((long) this.output.size() + 1, this.maximum);
            this.output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            requireSize((long) this.output.size() + length, this.maximum);
            this.output.write(bytes, offset, length);
        }

        private byte[] bytes() {
            return this.output.toByteArray();
        }
    }
}
