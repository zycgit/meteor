/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;

/** XML-RPC document grammar and standard wire values, independent of service dispatch. */
final class XmlRpcWireCodec {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd'T'HH:mm:ss") //
            .withResolverStyle(ResolverStyle.STRICT);
    private final        int               maxMessageSize;
    private final        int               maxDepth;
    private final        boolean           allowNil;

    XmlRpcWireCodec(ProtocolConfig config) {
        this.maxMessageSize = positiveOption(config, "maxMessageSize", 4 * 1024 * 1024);
        this.maxDepth = positiveOption(config, "maxDepth", 64);
        String nil = config.options().getOrDefault("allowNil", "false");
        if (!StringUtils.equalsIgnoreCase(nil, "true") && !StringUtils.equalsIgnoreCase(nil, "false")) {
            throw new IllegalArgumentException("allowNil must be true or false");
        }
        this.allowNil = Boolean.parseBoolean(nil);
    }

    XmlRpcCall decodeRequest(byte[] body) throws IOException {
        XMLStreamReader reader = this.open(body);
        try {
            // 先校验调用信封，再按顺序读取方法名与位置参数。
            this.start(reader, "methodCall");
            this.tag(reader);
            this.start(reader, "methodName");
            String methodName = this.text(reader);
            this.checkMethodName(methodName);
            int event = this.tag(reader);
            List<Object> parameters = new ArrayList<>();
            if (event == XMLStreamConstants.START_ELEMENT) {
                this.start(reader, "params");
                while (this.tag(reader) == XMLStreamConstants.START_ELEMENT) {
                    this.start(reader, "param");
                    this.tag(reader);
                    parameters.add(this.value(reader, 1));
                    this.tag(reader);
                    this.end(reader, "param");
                }
                this.end(reader, "params");
                this.tag(reader);
            }
            this.end(reader, "methodCall");
            this.documentEnd(reader);
            return new XmlRpcCall(methodName, parameters);
        } catch (XMLStreamException | IllegalArgumentException e) {
            throw new IOException("Invalid XML-RPC request", e);
        } finally {
            this.close(reader);
        }
    }

    Object decodeResponse(byte[] body) throws IOException {
        XMLStreamReader reader = this.open(body);
        try {
            // 响应只能包含一个结果或一个 fault，二者不能同时出现。
            this.start(reader, "methodResponse");
            this.tag(reader);
            boolean fault = StringUtils.equals(reader.getLocalName(), "fault");
            this.start(reader, fault ? "fault" : "params");
            this.tag(reader);
            if (!fault) {
                this.start(reader, "param");
                this.tag(reader);
            }
            Object result = this.value(reader, 1);
            this.tag(reader);
            if (!fault) {
                this.end(reader, "param");
                this.tag(reader);
            }
            this.end(reader, fault ? "fault" : "params");
            this.tag(reader);
            this.end(reader, "methodResponse");
            this.documentEnd(reader);
            if (fault) {
                throw this.fault(result);
            }
            return result;
        } catch (XMLStreamException | IllegalArgumentException e) {
            throw new IOException("Invalid XML-RPC response", e);
        } finally {
            this.close(reader);
        }
    }

    byte[] encodeRequest(String methodName, List<?> parameters) throws IOException {
        this.checkMethodName(methodName);
        StringBuilder xml = this.document("methodCall");
        this.append(xml, "<methodName>");
        this.escaped(xml, methodName);
        this.append(xml, "</methodName><params>");
        for (Object parameter : parameters) {
            this.append(xml, "<param>");
            this.value(xml, parameter, 1, new IdentityHashMap<>());
            this.append(xml, "</param>");
        }
        this.append(xml, "</params></methodCall>");
        return this.bytes(xml);
    }

    byte[] encodeResponse(Object value) throws IOException {
        StringBuilder xml = this.document("methodResponse");
        this.append(xml, "<params><param>");
        this.value(xml, value, 1, new IdentityHashMap<>());
        this.append(xml, "</param></params></methodResponse>");
        return this.bytes(xml);
    }

    byte[] encodeFault(int code, String message) throws IOException {
        if (message == null) {
            throw new IOException("XML-RPC faultString must be a string");
        }
        Map<String, Object> fault = new LinkedHashMap<>();
        fault.put("faultCode", code);
        fault.put("faultString", message);
        StringBuilder xml = this.document("methodResponse");
        this.append(xml, "<fault>");
        this.value(xml, fault, 1, new IdentityHashMap<>());
        this.append(xml, "</fault></methodResponse>");
        return this.bytes(xml);
    }

    private XMLStreamReader open(byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > this.maxMessageSize) {
            throw new IOException("XML-RPC body is empty or exceeds maxMessageSize");
        }
        // 每份文档独立创建解析器；禁止 DTD、实体声明和外部资源读取。
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are prohibited");
        });
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(body));
            String version = reader.getVersion();
            if (version != null && !StringUtils.equals(version, "1.0")) {
                throw new XMLStreamException("XML-RPC requires XML 1.0");
            }
            this.tag(reader);
            return reader;
        } catch (XMLStreamException e) {
            if (reader != null) {
                this.close(reader);
            }
            throw new IOException("Malformed XML-RPC document", e);
        }
    }

    private int tag(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) {
                return event;
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!reader.isWhiteSpace()) {
                    throw new XMLStreamException("Unexpected text in XML-RPC structure");
                }
            } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw new XMLStreamException("DTD and entity declarations are prohibited");
            }
        }
        throw new XMLStreamException("Incomplete XML-RPC document");
    }

    private void start(XMLStreamReader reader, String name) throws XMLStreamException {
        boolean named = reader.isStartElement() && StringUtils.equals(name, reader.getLocalName());
        if (!named) {
            throw new XMLStreamException("Expected XML-RPC element: " + name);
        }
        String namespace = reader.getNamespaceURI();
        boolean namespaced = namespace != null && !namespace.isEmpty();
        if (namespaced || reader.getNamespaceCount() != 0 || reader.getAttributeCount() != 0) {
            throw new XMLStreamException("XML-RPC elements must not declare namespaces or attributes");
        }
    }

    private void end(XMLStreamReader reader, String name) throws XMLStreamException {
        if (!reader.isEndElement() || !StringUtils.equals(name, reader.getLocalName())) {
            throw new XMLStreamException("Expected closing XML-RPC element: " + name);
        }
    }

    private String text(XMLStreamReader reader) throws XMLStreamException {
        String name = reader.getLocalName();
        this.start(reader, name);
        StringBuilder value = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                this.end(reader, name);
                return value.toString();
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                value.append(reader.getText());
            } else if (event != XMLStreamConstants.COMMENT && event != XMLStreamConstants.PROCESSING_INSTRUCTION) {
                throw new XMLStreamException("XML-RPC scalar must contain text only");
            }
        }
        throw new XMLStreamException("Incomplete XML-RPC scalar");
    }

    private Object value(XMLStreamReader reader, int depth) throws XMLStreamException, IOException {
        this.depth(depth);
        this.start(reader, "value");
        StringBuilder implicit = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                this.end(reader, "value");
                return implicit.toString();
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                implicit.append(reader.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                if (!implicit.toString().trim().isEmpty()) {
                    throw new XMLStreamException("XML-RPC value mixes text and typed content");
                }
                Object result = this.typedValue(reader, depth);
                this.tag(reader);
                this.end(reader, "value");
                return result;
            } else if (event != XMLStreamConstants.COMMENT && event != XMLStreamConstants.PROCESSING_INSTRUCTION) {
                throw new XMLStreamException("Invalid XML-RPC value");
            }
        }
        throw new XMLStreamException("Incomplete XML-RPC value");
    }

    private Object typedValue(XMLStreamReader reader, int depth) throws XMLStreamException, IOException {
        String type = reader.getLocalName();
        this.start(reader, type);
        if (StringUtils.equals(type, "array")) {
            this.tag(reader);
            this.start(reader, "data");
            List<Object> values = new ArrayList<>();
            while (this.tag(reader) == XMLStreamConstants.START_ELEMENT) {
                values.add(this.value(reader, depth + 1));
            }
            this.end(reader, "data");
            this.tag(reader);
            this.end(reader, "array");
            return values;
        }
        if (StringUtils.equals(type, "struct")) {
            Map<String, Object> values = new LinkedHashMap<>();
            while (this.tag(reader) == XMLStreamConstants.START_ELEMENT) {
                this.start(reader, "member");
                this.tag(reader);
                this.start(reader, "name");
                String name = this.text(reader);
                if (values.containsKey(name)) {
                    throw new XMLStreamException("Duplicate XML-RPC member: " + name);
                }
                this.tag(reader);
                values.put(name, this.value(reader, depth + 1));
                this.tag(reader);
                this.end(reader, "member");
            }
            this.end(reader, "struct");
            return values;
        }
        String text = this.text(reader);
        return this.scalar(type, text);
    }

    private Object scalar(String type, String text) throws IOException {
        try {
            switch (type) {
                case "string":
                    return text;
                case "int":
                case "i4":
                    if (!text.matches("[+-]?[0-9]+")) {
                        throw new IOException("XML-RPC integer must contain decimal digits");
                    }
                    return Integer.parseInt(text);
                case "boolean":
                    if (StringUtils.equals(text, "0")) {
                        return false;
                    } else if (StringUtils.equals(text, "1")) {
                        return true;
                    }
                    throw new IOException("XML-RPC boolean must be 0 or 1");
                case "double":
                    if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                        throw new IOException("Invalid XML-RPC double");
                    }
                    double value = Double.parseDouble(text);
                    if (!Double.isFinite(value)) {
                        throw new IOException("XML-RPC double must be finite");
                    }
                    return value;
                case "dateTime.iso8601":
                    if (!text.matches("[0-9]{8}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
                        throw new IOException("XML-RPC date must use yyyyMMdd'T'HH:mm:ss");
                    }
                    LocalDateTime date = LocalDateTime.parse(text, DATE_FORMAT);
                    return Date.from(date.toInstant(ZoneOffset.UTC));
                case "base64":
                    String compact = this.base64Text(text);
                    if (compact.length() % 4 != 0) {
                        throw new IOException("Invalid XML-RPC base64");
                    }
                    return Base64.getDecoder().decode(compact);
                case "nil":
                    if (this.allowNil && text.isEmpty()) {
                        return null;
                    }
                    throw new IOException("XML-RPC nil requires allowNil and empty content");
                default:
                    throw new IOException("Unsupported XML-RPC value type: " + type);
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("Invalid XML-RPC " + type + " value", e);
        }
    }

    private XmlRpcException fault(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("XML-RPC fault must be a struct");
        }
        Map<?, ?> fault = (Map<?, ?>) value;
        Object code = fault.get("faultCode");
        Object message = fault.get("faultString");
        if (fault.size() != 2 || !(code instanceof Integer) || !(message instanceof String)) {
            throw new IOException("XML-RPC fault requires faultCode and faultString");
        }
        return new XmlRpcException((Integer) code, (String) message);
    }

    private void documentEnd(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_DOCUMENT || event == XMLStreamConstants.COMMENT) {
                continue;
            }
            if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                continue;
            }
            if (event == XMLStreamConstants.CHARACTERS && reader.isWhiteSpace()) {
                continue;
            }
            throw new XMLStreamException("Unexpected content after XML-RPC document");
        }
    }

    private StringBuilder document(String root) throws IOException {
        StringBuilder xml = new StringBuilder();
        this.append(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><" + root + ">");
        return xml;
    }

    private void value(StringBuilder xml, Object value, int depth, //
            IdentityHashMap<Object, Boolean> parents) throws IOException {
        this.depth(depth);
        this.append(xml, "<value>");
        boolean smallInteger = value instanceof Byte || value instanceof Short;
        boolean integer = smallInteger || value instanceof Integer || value instanceof Long;
        if (value == null) {
            if (!this.allowNil) {
                throw new IOException("XML-RPC null requires allowNil");
            }
            this.append(xml, "<nil/>");
        } else if (value instanceof String) {
            this.append(xml, "<string>");
            this.escaped(xml, (String) value);
            this.append(xml, "</string>");
        } else if (value instanceof Boolean) {
            this.append(xml, (Boolean) value ? "<boolean>1</boolean>" : "<boolean>0</boolean>");
        } else if (integer) {
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IOException("XML-RPC integer exceeds signed 32-bit range");
            }
            this.append(xml, "<int>" + number + "</int>");
        } else if (value instanceof Float || value instanceof Double) {
            double decimal = ((Number) value).doubleValue();
            if (!Double.isFinite(decimal)) {
                throw new IOException("XML-RPC double must be finite");
            }
            String number = BigDecimal.valueOf(decimal).toPlainString();
            if (decimal == 0.0) {
                number = Double.toString(decimal);
            }
            this.append(xml, "<double>" + number + "</double>");
        } else if (value instanceof Date) {
            Instant instant = Instant.ofEpochMilli(((Date) value).getTime());
            LocalDateTime date = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            if (date.getYear() < 0 || date.getYear() > 9999) {
                throw new IOException("XML-RPC date year must contain four digits");
            }
            this.append(xml, "<dateTime.iso8601>" + DATE_FORMAT.format(date) + "</dateTime.iso8601>");
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > this.maxMessageSize) {
                throw new IOException("XML-RPC binary exceeds maxMessageSize");
            }
            this.append(xml, "<base64>" + Base64.getEncoder().encodeToString(bytes) + "</base64>");
        } else if (value instanceof List<?> || value instanceof Map<?, ?>) {
            this.compound(xml, value, depth, parents);
        } else {
            throw new IOException("Unsupported XML-RPC value: " + value.getClass().getName());
        }
        this.append(xml, "</value>");
    }

    private void compound(StringBuilder xml, Object value, int depth, //
            IdentityHashMap<Object, Boolean> parents) throws IOException {
        if (parents.put(value, true) != null) {
            throw new IOException("XML-RPC values must not contain cycles");
        }
        try {
            if (value instanceof List<?>) {
                this.append(xml, "<array><data>");
                for (Object item : (List<?>) value) {
                    this.value(xml, item, depth + 1, parents);
                }
                this.append(xml, "</data></array>");
            } else {
                this.append(xml, "<struct>");
                for (Map.Entry<?, ?> member : ((Map<?, ?>) value).entrySet()) {
                    if (!(member.getKey() instanceof String)) {
                        throw new IOException("XML-RPC struct keys must be strings");
                    }
                    this.append(xml, "<member><name>");
                    this.escaped(xml, (String) member.getKey());
                    this.append(xml, "</name>");
                    this.value(xml, member.getValue(), depth + 1, parents);
                    this.append(xml, "</member>");
                }
                this.append(xml, "</struct>");
            }
        } finally {
            parents.remove(value);
        }
    }

    private void escaped(StringBuilder xml, String value) throws IOException {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean control = codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r';
            boolean invalid = codePoint >= 0xD800 && codePoint <= 0xDFFF || codePoint == 0xFFFE || codePoint == 0xFFFF;
            if (control || invalid) {
                throw new IOException("XML-RPC text contains a character forbidden by XML 1.0");
            }
            switch (codePoint) {
                case '&':
                    this.append(xml, "&amp;");
                    break;
                case '<':
                    this.append(xml, "&lt;");
                    break;
                case '>':
                    this.append(xml, "&gt;");
                    break;
                case '\r':
                    this.append(xml, "&#13;");
                    break;
                default:
                    this.append(xml, new String(Character.toChars(codePoint)));
            }
        }
    }

    private void append(StringBuilder xml, String value) throws IOException {
        if ((long) xml.length() + value.length() > this.maxMessageSize) {
            throw new IOException("XML-RPC document exceeds maxMessageSize");
        }
        xml.append(value);
    }

    private String base64Text(String text) {
        StringBuilder compact = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\t' && character != '\n' && character != '\r' && character != ' ') {
                compact.append(character);
            }
        }
        return compact.toString();
    }

    private byte[] bytes(StringBuilder xml) throws IOException {
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > this.maxMessageSize) {
            throw new IOException("XML-RPC document exceeds maxMessageSize");
        }
        return bytes;
    }

    private void depth(int depth) throws IOException {
        if (depth > this.maxDepth) {
            throw new IOException("XML-RPC value exceeds maxDepth");
        }
    }

    private void checkMethodName(String methodName) throws IOException {
        if (methodName == null || !methodName.matches("[A-Za-z0-9_.:/]+")) {
            throw new IOException("Invalid XML-RPC method name");
        }
    }

    private void close(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // 解析器仅持有内存输入，不覆盖调用方已经获得的解码结果或异常。
        }
    }

    private static int positiveOption(ProtocolConfig config, String name, int defaultValue) {
        String configured = config.options().get(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(configured);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 使用包含选项名的统一错误信息。
        }
        throw new IllegalArgumentException(name + " must be a positive integer");
    }
}
