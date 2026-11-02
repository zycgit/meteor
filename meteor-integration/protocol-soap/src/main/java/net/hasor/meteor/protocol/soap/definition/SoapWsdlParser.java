/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import javax.xml.namespace.QName;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 将 WSDL 1.1 的 document/RPC、literal/encoded 绑定整理为运行时操作表。 */
final class SoapWsdlParser {
    static final  String                      WSDL_NAMESPACE      = "http://schemas.xmlsoap.org/wsdl/";
    static final  String                      XSD_NAMESPACE       = "http://www.w3.org/2001/XMLSchema";
    static final  String                      SOAP_HTTP_TRANSPORT = "http://schemas.xmlsoap.org/soap/http";
    private final Collection<SoapXmlResource> resources;
    private final SoapXmlResource             entry;
    private final String                      parameterStyle;
    private final SoapSchemaParser            schemas;

    SoapWsdlParser(Collection<SoapXmlResource> resources, SoapXmlResource entry, SoapSchemaParser schemas, //
            String parameterStyle) {
        this.resources = resources;
        this.entry = entry;
        this.parameterStyle = parameterStyle;
        this.schemas = schemas;
    }

    SoapDefinition parse() throws IOException {
        Element root = this.entry.document().getDocumentElement();
        if (!this.isElement(root, WSDL_NAMESPACE, "definitions")) {
            throw new IOException("Web Services entry must be a WSDL 1.1 definitions document: " + this.entry.source());
        }

        String targetNamespace = this.requiredAttribute(root, "targetNamespace", "WSDL targetNamespace");
        ServiceBinding selected = this.readServiceBinding(root);

        // 先整理可复用的消息、包装元素和端口操作。
        Map<QName, List<MessagePart>> messages = this.readMessages();
        SoapSchemaParser schemas = this.schemas;
        Map<QName, Map<String, PortOperation>> portTypes = this.readPortTypes();

        // 入口服务引用的 SOAP binding 决定 action 和 document/literal 约束。
        List<SoapOperation> operations = this.readBinding(selected, messages, schemas, portTypes);
        if (operations.isEmpty()) {
            String message = "WSDL does not declare a SOAP operation: ";
            throw new IOException(message + this.entry.source());
        }

        try {
            return new SoapDefinition(selected.version, targetNamespace, operations);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid SOAP operation index: " + failure.getMessage(), failure);
        }
    }

    private ServiceBinding readServiceBinding(Element definitions) throws IOException {
        ServiceBinding selected = null;
        for (Element service : this.children(definitions, WSDL_NAMESPACE, "service")) {
            for (Element port : this.children(service, WSDL_NAMESPACE, "port")) {
                for (SoapVersion version : SoapVersion.values()) {
                    List<Element> addresses = this.children(port, version.bindingNamespace(), "address");
                    if (addresses.isEmpty()) {
                        continue;
                    }
                    if (addresses.size() != 1 || selected != null) {
                        String message = "WSDL entry declares multiple SOAP service ports: ";
                        throw new IOException(message + this.entry.source());
                    }
                    String binding = this.requiredAttribute(port, "binding", "WSDL service port binding");
                    selected = new ServiceBinding(this.qName(port, binding), version);
                }
            }
        }
        if (selected == null) {
            throw new IOException("WSDL entry does not declare a SOAP service port: " + this.entry.source());
        }
        return selected;
    }

    private Map<QName, List<MessagePart>> readMessages() throws IOException {
        Map<QName, List<MessagePart>> messages = new LinkedHashMap<>();
        for (SoapXmlResource resource : this.resources) {
            Element definitions = resource.document().getDocumentElement();
            if (!this.isElement(definitions, WSDL_NAMESPACE, "definitions")) {
                continue;
            }
            String namespace = this.requiredAttribute(definitions, "targetNamespace", "WSDL targetNamespace");
            for (Element message : this.children(definitions, WSDL_NAMESPACE, "message")) {
                String name = this.requiredAttribute(message, "name", "WSDL message name");
                List<MessagePart> parts = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (Element part : this.children(message, WSDL_NAMESPACE, "part")) {
                    String partName = this.requiredAttribute(part, "name", "WSDL message part name");
                    if (!names.add(partName)) {
                        throw new IOException("Duplicate WSDL message part: " + name + " -> " + partName);
                    }
                    String elementName = part.getAttribute("element");
                    String typeName = part.getAttribute("type");
                    if (elementName.isBlank() == typeName.isBlank()) {
                        String reason = "WSDL message part must declare exactly one element or type: ";
                        throw new IOException(reason + partName);
                    }
                    QName element = elementName.isBlank() ? null : this.qName(part, elementName);
                    QName type = typeName.isBlank() ? null : this.qName(part, typeName);
                    parts.add(new MessagePart(partName, element, type));
                }
                QName messageName = new QName(namespace, name);
                if (messages.putIfAbsent(messageName, List.copyOf(parts)) != null) {
                    throw new IOException("Duplicate WSDL message: " + messageName);
                }
            }
        }
        return messages;
    }

    private Map<QName, Map<String, PortOperation>> readPortTypes() throws IOException {
        Map<QName, Map<String, PortOperation>> portTypes = new LinkedHashMap<>();
        for (SoapXmlResource resource : this.resources) {
            Element definitions = resource.document().getDocumentElement();
            if (!this.isElement(definitions, WSDL_NAMESPACE, "definitions")) {
                continue;
            }

            String namespace = this.requiredAttribute(definitions, "targetNamespace", "WSDL targetNamespace");
            for (Element portType : this.children(definitions, WSDL_NAMESPACE, "portType")) {
                String portName = this.requiredAttribute(portType, "name", "WSDL portType name");
                Map<String, PortOperation> operations = new LinkedHashMap<>();
                for (Element operation : this.children(portType, WSDL_NAMESPACE, "operation")) {
                    PortOperation parsed = this.readPortOperation(operation);
                    if (operations.putIfAbsent(parsed.name(), parsed) != null) {
                        throw new IOException("Duplicate WSDL portType operation: " + parsed.name());
                    }
                }
                QName portTypeName = new QName(namespace, portName);
                if (portTypes.putIfAbsent(portTypeName, operations) != null) {
                    throw new IOException("Duplicate WSDL portType: " + portTypeName);
                }
            }
        }
        return portTypes;
    }

    private PortOperation readPortOperation(Element operation) throws IOException {
        String name = this.requiredAttribute(operation, "name", "WSDL operation name");
        Element input = this.firstChild(operation, WSDL_NAMESPACE, "input");
        if (input == null) {
            throw new IOException("SOAP operation does not declare input: " + name);
        }

        String inputMessage = this.requiredAttribute(input, "message", "WSDL operation input message");
        Element output = this.firstChild(operation, WSDL_NAMESPACE, "output");
        QName outputMessage = null;
        if (output != null) {
            String outputName = this.requiredAttribute(output, "message", "WSDL operation output message");
            outputMessage = this.qName(output, outputName);
        }
        Map<String, QName> faults = new LinkedHashMap<>();
        for (Element fault : this.children(operation, WSDL_NAMESPACE, "fault")) {
            String faultName = this.requiredAttribute(fault, "name", "WSDL fault name");
            String faultMessage = this.requiredAttribute(fault, "message", "WSDL fault message");
            if (faults.putIfAbsent(faultName, this.qName(fault, faultMessage)) != null) {
                throw new IOException("Duplicate WSDL operation fault: " + faultName);
            }
        }
        String order = operation.getAttribute("parameterOrder").trim();
        List<String> parameters = order.isEmpty() ? List.of() : Arrays.asList(order.split("\\s+"));
        return new PortOperation(name, this.qName(input, inputMessage), outputMessage, faults, parameters);
    }

    private List<SoapOperation> readBinding(ServiceBinding selected, Map<QName, List<MessagePart>> messages, //
            SoapSchemaParser schemas, Map<QName, Map<String, PortOperation>> portTypes) throws IOException {
        QName bindingName = selected.name;
        String bindingNamespace = selected.version.bindingNamespace();
        Element selectedBinding = null;
        for (SoapXmlResource resource : this.resources) {
            Element definitions = resource.document().getDocumentElement();
            if (!this.isElement(definitions, WSDL_NAMESPACE, "definitions")) {
                continue;
            }

            String namespace = this.requiredAttribute(definitions, "targetNamespace", "WSDL targetNamespace");
            for (Element binding : this.children(definitions, WSDL_NAMESPACE, "binding")) {
                String name = this.requiredAttribute(binding, "name", "WSDL binding name");
                QName candidate = new QName(namespace, name);
                if (!bindingName.equals(candidate)) {
                    continue;
                }
                if (selectedBinding != null) {
                    throw new IOException("WSDL declares the selected binding more than once: " + bindingName);
                }
                selectedBinding = binding;
            }
        }

        if (selectedBinding == null) {
            throw new IOException("SOAP service port references an unknown binding: " + bindingName);
        }

        Element soapBinding = this.firstChild(selectedBinding, bindingNamespace, "binding");
        if (soapBinding == null) {
            throw new IOException("WSDL service port and binding use different SOAP versions: " + bindingName);
        }
        this.requireHttpTransport(soapBinding, bindingName, selected.version);
        String style = this.style(soapBinding, "document");

        String portTypeName = this.requiredAttribute(selectedBinding, "type", "WSDL binding type");
        Map<String, PortOperation> portOperations = portTypes.get(this.qName(selectedBinding, portTypeName));
        if (portOperations == null) {
            throw new IOException("SOAP binding references an unknown portType: " + portTypeName);
        }

        List<SoapOperation> operations = new ArrayList<>();
        for (Element operation : this.children(selectedBinding, WSDL_NAMESPACE, "operation")) {
            SoapOperation parsed = this.readBindingOperation(operation, selected.version, style, //
                    portOperations, messages, schemas);
            operations.add(parsed);
        }
        return operations;
    }

    private SoapOperation readBindingOperation(Element operation, SoapVersion version, String bindingStyle, //
            Map<String, PortOperation> portOperations, Map<QName, List<MessagePart>> messages, //
            SoapSchemaParser schemas) throws IOException {
        String name = this.requiredAttribute(operation, "name", "WSDL binding operation name");
        PortOperation portOperation = portOperations.get(name);
        if (portOperation == null) {
            throw new IOException("SOAP binding references an unknown operation: " + name);
        }
        Element soapOperation = this.firstChild(operation, version.bindingNamespace(), "operation");
        if (soapOperation == null) {
            throw new IOException("SOAP binding operation is missing soap:operation: " + name);
        }
        String style = this.style(soapOperation, bindingStyle);
        SoapBody input = this.readBody(operation, "input", name, version, style, //
                messages.get(portOperation.inputMessage()), schemas, portOperation.parameterOrder);
        SoapBody output = null;
        if (!portOperation.oneWay()) {
            output = this.readBody(operation, "output", name, version, style, //
                    messages.get(portOperation.outputMessage()), schemas, List.of());
        }
        String action = soapOperation.getAttribute("soapAction");
        boolean actionRequired = this.actionRequired(soapOperation, version, action);
        List<SoapHeaderBinding> inputHeaders = this.readHeaders(operation, "input", version, messages, schemas);
        List<SoapHeaderBinding> outputHeaders = this.readHeaders(operation, "output", version, messages, schemas);
        Map<String, SoapFaultBinding> faults = this.readFaults(operation, portOperation, version, messages, schemas);
        return new SoapOperation(name, action, style, input, output, actionRequired, //
                inputHeaders, outputHeaders, faults);
    }

    private List<SoapHeaderBinding> readHeaders(Element operation, String direction, SoapVersion version, //
            Map<QName, List<MessagePart>> messages, SoapSchemaParser schemas) throws IOException {
        Element message = this.firstChild(operation, WSDL_NAMESPACE, direction);
        if (message == null) {
            return List.of();
        }
        List<SoapHeaderBinding> headers = new ArrayList<>();
        Set<QName> elements = new HashSet<>();
        for (Element header : this.children(message, version.bindingNamespace(), "header")) {
            SoapHeaderBinding parsed = this.readHeader(header, version, messages, schemas);
            if (!elements.add(parsed.part().element())) {
                throw new IOException("Duplicate SOAP header element: " + parsed.part().element());
            }
            headers.add(parsed);
        }
        return List.copyOf(headers);
    }

    private SoapHeaderBinding readHeader(Element header, SoapVersion version, //
            Map<QName, List<MessagePart>> messages, SoapSchemaParser schemas) throws IOException {
        String messageName = this.requiredAttribute(header, "message", "SOAP header message");
        List<MessagePart> message = messages.get(this.qName(header, messageName));
        if (message == null) {
            throw new IOException("SOAP header references an unknown message: " + messageName);
        }
        String partName = this.requiredAttribute(header, "part", "SOAP header part");
        MessagePart selected = null;
        for (MessagePart part : message) {
            if (StringUtils.equals(partName, part.name)) {
                selected = part;
                break;
            }
        }
        if (selected == null) {
            throw new IOException("SOAP header references an unknown message part: " + partName);
        }
        this.validateNamespace(header, version);
        String use = this.boundUse(header);
        String encoding = this.encodingStyle(header, version, "rpc", use);
        SoapPart part = this.boundPart(header, selected, use, schemas);
        List<SoapHeaderBinding> faults = new ArrayList<>();
        for (Element fault : this.children(header, version.bindingNamespace(), "headerfault")) {
            if (StringUtils.equals("headerfault", header.getLocalName())) {
                throw new IOException("SOAP headerfault cannot contain another headerfault.");
            }
            faults.add(this.readHeader(fault, version, messages, schemas));
        }
        return new SoapHeaderBinding(part, use, encoding, faults);
    }

    private Map<String, SoapFaultBinding> readFaults(Element operation, PortOperation portOperation, //
            SoapVersion version, Map<QName, List<MessagePart>> messages, SoapSchemaParser schemas) throws IOException {
        Map<String, SoapFaultBinding> faults = new LinkedHashMap<>();
        for (Element fault : this.children(operation, WSDL_NAMESPACE, "fault")) {
            String name = this.requiredAttribute(fault, "name", "WSDL binding fault name");
            QName messageName = portOperation.faults.get(name);
            List<MessagePart> message = messages.get(messageName);
            if (message == null) {
                throw new IOException("SOAP fault references an unknown fault message: " + name);
            }
            if (message.size() != 1) {
                throw new IOException("SOAP fault message must contain one part: " + name);
            }
            Element binding = this.firstChild(fault, version.bindingNamespace(), "fault");
            if (binding == null || !StringUtils.equals(name, binding.getAttribute("name"))) {
                throw new IOException("SOAP fault binding name must match its WSDL fault: " + name);
            }
            this.validateNamespace(binding, version);
            String use = this.boundUse(binding);
            String encoding = this.encodingStyle(binding, version, "rpc", use);
            SoapPart part = this.boundPart(binding, message.get(0), use, schemas);
            SoapFaultBinding parsed = new SoapFaultBinding(part, use, encoding);
            if (faults.putIfAbsent(name, parsed) != null) {
                throw new IOException("Duplicate SOAP fault binding: " + name);
            }
        }
        if (faults.size() != portOperation.faults.size()) {
            throw new IOException("SOAP binding must bind every declared WSDL fault: " + portOperation.name());
        }
        return faults;
    }

    private String boundUse(Element binding) throws IOException {
        String use = this.requiredAttribute(binding, "use", "SOAP part use");
        if (!StringUtils.equals("literal", use) && !StringUtils.equals("encoded", use)) {
            throw new IOException("SOAP part use must be literal or encoded: " + use);
        }
        return use;
    }

    private SoapPart boundPart(Element binding, MessagePart part, String use, //
            SoapSchemaParser schemas) throws IOException {
        if (StringUtils.equals("literal", use)) {
            if (part.element == null || !schemas.hasElement(part.element)) {
                throw new IOException("Literal SOAP header/fault requires a declared global element: " + part.name);
            }
            return new SoapPart(part.name, part.element, schemas.elementType(part.element), 1, 1);
        }
        if (part.type == null) {
            throw new IOException("Encoded SOAP header/fault requires a type part: " + part.name);
        }
        this.requireType(part.type, schemas);
        String namespace = this.requiredAttribute(binding, "namespace", "Encoded SOAP part namespace");
        return new SoapPart(part.name, new QName(namespace, part.name), part.type, 1, 1);
    }

    private SoapBody readBody(Element operation, String direction, String operationName, SoapVersion version, //
            String style, List<MessagePart> messageParts, SoapSchemaParser schemas, //
            List<String> parameterOrder) throws IOException {
        if (messageParts == null) {
            throw new IOException("SOAP operation references an unknown " + direction + " message: " + operationName);
        }
        Element message = this.firstChild(operation, WSDL_NAMESPACE, direction);
        Element body = message == null ? null : this.firstChild(message, version.bindingNamespace(), "body");
        if (body == null) {
            throw new IOException("SOAP operation is missing " + direction + " body: " + operationName);
        }
        this.validateNamespace(body, version);
        String use = body.getAttribute("use");
        if (use.isBlank()) {
            use = "literal";
        }
        if (!StringUtils.equals("literal", use) && !StringUtils.equals("encoded", use)) {
            throw new IOException("SOAP body use must be literal or encoded: " + use);
        }
        String encodingStyle = this.encodingStyle(body, version, style, use);
        List<MessagePart> selected = this.selectParts(body, messageParts);
        boolean output = StringUtils.equals("output", direction);
        String wrapperName = output ? operationName + "Response" : operationName;
        if (StringUtils.equals("rpc", style)) {
            selected = this.orderParameters(selected, parameterOrder);
            String namespace = this.requiredAttribute(body, "namespace", "RPC SOAP body namespace");
            List<SoapPart> parts = new ArrayList<>();
            for (MessagePart part : selected) {
                if (part.type == null) {
                    throw new IOException("RPC SOAP message part must declare a type: " + part.name);
                }
                this.requireType(part.type, schemas);
                parts.add(new SoapPart(part.name, new QName("", part.name), part.type, 1, 1));
            }
            return new SoapBody(new QName(namespace, wrapperName), parts, List.of(), use, encodingStyle);
        }

        if (StringUtils.equals("encoded", use)) {
            if (StringUtils.equals("wrapped", this.parameterStyle)) {
                throw new IOException("Document encoded SOAP requires bare parameters.");
            }
            List<SoapPart> parts = new ArrayList<>();
            String namespace = body.getAttribute("namespace");
            for (MessagePart part : selected) {
                if (part.type != null) {
                    this.requireType(part.type, schemas);
                    parts.add(new SoapPart(part.name, new QName(namespace, part.name), part.type, 1, 1));
                } else if (schemas.hasElement(part.element)) {
                    parts.add(new SoapPart(part.name, part.element, schemas.elementType(part.element), 1, 1));
                } else {
                    throw new IOException("SOAP message references an unknown XML Schema element: " + part.element);
                }
            }
            return new SoapBody(null, parts, List.of(), use, encodingStyle);
        }

        // document/literal bare 使用全局 element 本身；wrapped 是单个约定包装元素的参数映射。
        for (MessagePart part : selected) {
            if (part.element == null) {
                throw new IOException("Document SOAP message part must declare an element: " + part.name);
            }
            if (!schemas.hasElement(part.element)) {
                throw new IOException("SOAP message references an unknown XML Schema element: " + part.element);
            }
        }
        if (selected.size() == 1 && this.unwrap(selected.get(0), wrapperName)) {
            QName wrapper = selected.get(0).element;
            try {
                List<SoapPart> parts = schemas.wrapperParts(wrapper);
                if (parts != null) {
                    this.requireUniqueParameters(parts, operationName);
                    return new SoapBody(wrapper, parts, schemas.wrapperChoices(wrapper), use, encodingStyle);
                }
            } catch (SoapSchemaMappingException failure) {
                if (!StringUtils.equals("auto", this.parameterStyle)) {
                    throw failure;
                }
            }
            if (StringUtils.equals("wrapped", this.parameterStyle)) {
                throw new IOException("SOAP wrapped element must declare a complexType: " + wrapper);
            }
        }
        List<SoapPart> parts = new ArrayList<>();
        for (MessagePart part : selected) {
            parts.add(new SoapPart(part.name, part.element, schemas.elementType(part.element), 1, 1));
        }
        return new SoapBody(null, parts, List.of(), use, encodingStyle);
    }

    private List<MessagePart> orderParameters(List<MessagePart> parts, List<String> parameterOrder) throws IOException {
        if (parameterOrder.isEmpty()) {
            return parts;
        }
        Map<String, MessagePart> remaining = new LinkedHashMap<>();
        for (MessagePart part : parts) {
            remaining.put(part.name, part);
        }
        Set<String> names = new HashSet<>();
        List<MessagePart> result = new ArrayList<>();
        for (String name : parameterOrder) {
            if (!names.add(name)) {
                throw new IOException("RPC parameterOrder declares a duplicate parameter: " + name);
            }
            MessagePart part = remaining.remove(name);
            if (part != null) {
                result.add(part);
            }
        }
        if (!remaining.isEmpty()) {
            throw new IOException("RPC parameterOrder omits input message parts: " + remaining.keySet());
        }
        return result;
    }

    private boolean unwrap(MessagePart part, String wrapperName) {
        if (StringUtils.equals("bare", this.parameterStyle)) {
            return false;
        }
        return StringUtils.equals("wrapped", this.parameterStyle) //
                || StringUtils.equals(wrapperName, part.element.getLocalPart());
    }

    private List<MessagePart> selectParts(Element body, List<MessagePart> parts) throws IOException {
        if (!body.hasAttribute("parts")) {
            return parts;
        }
        Set<String> selected = new LinkedHashSet<>();
        String names = body.getAttribute("parts").trim();
        if (!names.isEmpty()) {
            for (String name : names.split("\\s+")) {
                if (!selected.add(name)) {
                    throw new IOException("SOAP body declares a duplicate part: " + name);
                }
            }
        }
        List<MessagePart> result = new ArrayList<>();
        for (MessagePart part : parts) {
            if (selected.remove(part.name)) {
                result.add(part);
            }
        }
        if (!selected.isEmpty()) {
            throw new IOException("SOAP body references an unknown message part: " + selected);
        }
        return result;
    }

    private String encodingStyle(Element body, SoapVersion version, String style, String use) throws IOException {
        String encoding = body.getAttribute("encodingStyle").trim();
        if (StringUtils.equals("encoded", use)) {
            if (version == SoapVersion.SOAP_12 && !StringUtils.equals("rpc", style) && !encoding.isEmpty()) {
                throw new IOException("SOAP 1.2 document binding cannot declare encodingStyle.");
            }
            String expected = version == SoapVersion.SOAP_11 //
                    ? "http://schemas.xmlsoap.org/soap/encoding/" : "http://www.w3.org/2003/05/soap-encoding";
            if (encoding.isEmpty()) {
                return expected;
            }
            if (!StringUtils.equals(expected, encoding)) {
                throw new IOException("Unsupported SOAP encodingStyle: " + encoding);
            }
        } else if (version == SoapVersion.SOAP_12 && !encoding.isEmpty()) {
            throw new IOException("SOAP 1.2 literal body cannot declare encodingStyle.");
        }
        return encoding;
    }

    private void requireType(QName type, SoapSchemaParser schemas) throws IOException {
        if (!schemas.hasType(type)) {
            throw new IOException("SOAP message references an unknown XML Schema type: " + type);
        }
    }

    private void validateNamespace(Element binding, SoapVersion version) throws IOException {
        if (version != SoapVersion.SOAP_12 || !binding.hasAttribute("namespace")) {
            return;
        }
        String namespace = binding.getAttribute("namespace");
        try {
            if (!new URI(namespace).isAbsolute()) {
                throw new IOException("SOAP 1.2 binding namespace must be an absolute URI: " + namespace);
            }
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid SOAP 1.2 binding namespace URI: " + namespace, failure);
        }
    }

    private boolean actionRequired(Element operation, SoapVersion version, String action) throws IOException {
        if (version == SoapVersion.SOAP_11) {
            return true;
        }
        String required = operation.getAttribute("soapActionRequired");
        boolean actionRequired = required.isBlank() || StringUtils.equals("true", required) //
                || StringUtils.equals("1", required);
        if (!actionRequired && !StringUtils.equals("false", required) && !StringUtils.equals("0", required)) {
            throw new IOException("SOAP 1.2 soapActionRequired must be an XML Schema boolean: " + required);
        }
        if (actionRequired && !operation.hasAttribute("soapAction")) {
            throw new IOException("SOAP 1.2 operation requires soapAction when soapActionRequired is true.");
        }
        if (operation.hasAttribute("soapAction")) {
            try {
                if (!new URI(action).isAbsolute()) {
                    throw new IOException("SOAP 1.2 soapAction must be an absolute URI: " + action);
                }
            } catch (URISyntaxException failure) {
                throw new IOException("Invalid SOAP 1.2 soapAction URI: " + action, failure);
            }
        }
        return actionRequired;
    }

    private void requireUniqueParameters(List<SoapPart> parameters, String operationName) throws IOException {
        Set<QName> names = new HashSet<>();
        for (SoapPart parameter : parameters) {
            if (!names.add(parameter.element())) {
                String message = "SOAP input wrapper declares a duplicate parameter QName: ";
                throw new SoapSchemaMappingException(message + operationName + " -> " + parameter.element());
            }
        }
    }

    private String style(Element element, String inherited) throws IOException {
        String style = element.getAttribute("style");
        if (style.isBlank()) {
            return inherited;
        }
        if (!StringUtils.equals("document", style) && !StringUtils.equals("rpc", style)) {
            throw new IOException("SOAP style must be document or rpc: " + style);
        }
        return style;
    }

    private void requireHttpTransport(Element soapBinding, QName bindingName, SoapVersion version) throws IOException {
        String transport = this.requiredAttribute(soapBinding, "transport", "SOAP binding transport");
        if (!StringUtils.equals(SOAP_HTTP_TRANSPORT, transport)) {
            String message = "SOAP binding must use the SOAP " + version.displayName() + " HTTP transport: ";
            throw new IOException(message + bindingName);
        }
    }

    private QName qName(Element context, String value) throws IOException {
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
        return new QName(namespace == null ? "" : namespace, localName);
    }

    private String requiredAttribute(Element element, String name, String description) throws IOException {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new IOException(description + " is required.");
        }
        return value;
    }

    private List<Element> children(Element parent, String namespace, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node child = nodes.item(index);
            if (!(child instanceof Element element)) {
                continue;
            }
            if (!StringUtils.equals(namespace, element.getNamespaceURI())) {
                continue;
            }
            if (localName == null || StringUtils.equals(localName, element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    private Element firstChild(Element parent, String namespace, String localName) throws IOException {
        List<Element> children = this.children(parent, namespace, localName);
        if (children.size() > 1) {
            throw new IOException("Duplicate WSDL binding element: " + new QName(namespace, localName));
        }
        return children.isEmpty() ? null : children.get(0);
    }

    private boolean isElement(Element element, String namespace, String localName) {
        return StringUtils.equals(namespace, element.getNamespaceURI()) && //
                StringUtils.equals(localName, element.getLocalName());
    }

    /** 入口端口同时决定绑定名称和 SOAP 版本。 */
    private static final class ServiceBinding {
        private final QName       name;
        private final SoapVersion version;

        private ServiceBinding(QName name, SoapVersion version) {
            this.name = name;
            this.version = version;
        }

        public QName name() {
            return this.name;
        }

        public SoapVersion version() {
            return this.version;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ServiceBinding that)) {
                return false;
            }
            return ObjectUtils.equals(this.name, that.name) && this.version == that.version;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.version);
        }

        @Override
        public String toString() {
            return "ServiceBinding[name=" + this.name + ", version=" + this.version + "]";
        }
    }

    /** WSDL message 的抽象 part，element 与 type 必须恰好声明一个。 */
    private static final class MessagePart {
        private final String name;
        private final QName  element;
        private final QName  type;

        private MessagePart(String name, QName element, QName type) {
            this.name = name;
            this.element = element;
            this.type = type;
        }

        public String name() {
            return this.name;
        }

        public QName element() {
            return this.element;
        }

        public QName type() {
            return this.type;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof MessagePart that)) {
                return false;
            }
            return StringUtils.equals(this.name, that.name) && //
                    ObjectUtils.equals(this.element, that.element) && //
                    ObjectUtils.equals(this.type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.element, this.type);
        }

        @Override
        public String toString() {
            return "MessagePart[name=" + this.name + ", element=" + this.element + ", type=" + this.type + "]";
        }
    }

    /** portType 中尚未叠加 SOAP binding 信息的操作。 */
    private static final class PortOperation {
        private final String             name;
        private final QName              inputMessage;
        private final QName              outputMessage;
        private final Map<String, QName> faults;
        private final List<String>       parameterOrder;

        private PortOperation(String name, QName inputMessage, QName outputMessage, Map<String, QName> faults, //
                List<String> parameterOrder) {
            this.name = name;
            this.inputMessage = inputMessage;
            this.outputMessage = outputMessage;
            this.faults = Map.copyOf(faults);
            this.parameterOrder = List.copyOf(parameterOrder);
        }

        public String name() {
            return this.name;
        }

        public QName inputMessage() {
            return this.inputMessage;
        }

        public QName outputMessage() {
            return this.outputMessage;
        }

        public Map<String, QName> faults() {
            return this.faults;
        }

        public List<String> parameterOrder() {
            return this.parameterOrder;
        }

        private boolean oneWay() {
            return this.outputMessage == null;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof PortOperation that)) {
                return false;
            }
            return StringUtils.equals(this.name, that.name) && //
                    ObjectUtils.equals(this.inputMessage, that.inputMessage) && //
                    ObjectUtils.equals(this.outputMessage, that.outputMessage) && //
                    ObjectUtils.equals(this.faults, that.faults) && //
                    ObjectUtils.equals(this.parameterOrder, that.parameterOrder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.inputMessage, this.outputMessage, this.faults, this.parameterOrder);
        }

        @Override
        public String toString() {
            return "PortOperation[name=" + this.name + ", inputMessage=" + this.inputMessage + //
                    ", outputMessage=" + this.outputMessage + ", faults=" + this.faults + //
                    ", parameterOrder=" + this.parameterOrder + "]";
        }
    }
}
