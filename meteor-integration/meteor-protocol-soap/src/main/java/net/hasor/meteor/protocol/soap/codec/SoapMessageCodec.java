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
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.soap.SoapFault;
import net.hasor.meteor.protocol.soap.SoapGet;
import net.hasor.meteor.protocol.soap.SoapHeaderHandler;
import net.hasor.meteor.protocol.soap.definition.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** SOAP XML、HTTP 和 Meteor Payload 之间的映射。 */
public final class SoapMessageCodec {
    private static final String      SOAP_ACTOR_NEXT  = "http://schemas.xmlsoap.org/soap/actor/next";
    private static final String      METEOR_NAMESPACE = "urn:hasor:meteor:soap";
    private static final Set<String> RESERVED_HEADERS = Set.of(//
            "connection", "content-length", "content-type", "host", "message", "soapaction", "transfer-encoding");

    private final ProtocolConfig    config;
    private final SoapVersion       version;
    private final SoapHeaderHandler headerHandler;
    private final XmlMapper         mapper;
    private final SoapBodyCodec     bodies;

    public SoapMessageCodec(ProtocolConfig config, SoapHeaderHandler headerHandler) {
        this.config = config;
        this.version = SoapVersion.from(config);
        this.headerHandler = headerHandler;
        this.mapper = newXmlMapper();
        this.bodies = new SoapBodyCodec(this.version, this.mapper);
    }

    /** 复用运行期结果映射校验发布服务的 Java 返回类型。 */
    public static Type[] resultTypes(SoapBody binding, Type resultType) {
        return new SoapResultMapping(binding, resultType, newXmlMapper()).types();
    }

    static XmlMapper newXmlMapper() {
        return XmlMapper.builder() //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN) //
                .defaultUseWrapper(false) //
                .build();
    }

    public SoapInboundResult receive(HttpRequest input, MetBindInfo<?> service, //
            SoapDefinitionResource resource, long requestId, int requestTimeout) throws Exception {
        boolean get = StringUtils.equalsIgnoreCase(input.method(), "GET");
        boolean head = StringUtils.equalsIgnoreCase(input.method(), "HEAD");
        if (get || head) {
            String query = this.query(input.uri());
            if (query != null && query.startsWith("method=")) {
                if (head || this.version != SoapVersion.SOAP_12) {
                    return SoapInboundResult.response(this.simple(405));
                }
                return this.getResponse(input, service, resource, query.substring("method=".length()), //
                        requestId, requestTimeout);
            }
            return SoapInboundResult.response(this.simple(404));
        }
        if (!StringUtils.equalsIgnoreCase(input.method(), "POST")) {
            return SoapInboundResult.response(this.methodNotAllowed());
        }

        if (input.stream() != null || input.body().length > this.maxMessageSize()) {
            return SoapInboundResult.response(this.simple(413));
        }

        Map<String, String> mediaParameters;
        SoapAttachmentCodec.Message message;
        Charset charset;
        try {
            String contentType = this.singleHeader(input.headers(), "Content-Type");
            message = SoapAttachmentCodec.decode(input.body(), contentType, this.version, this.maxMessageSize());
            mediaParameters = this.mediaParameters(message.contentType());
            charset = this.soapCharset(mediaParameters);
        } catch (IllegalArgumentException failure) {
            return SoapInboundResult.response(this.simple(415));
        }

        try {
            // 按 Body 的 QName 序列与 action 选择操作，兼容 bare 和 RPC 编码引用。
            Document document = this.parse(message.body(), charset);
            Element envelope = document.getDocumentElement();
            Element body = this.validateEnvelope(envelope);
            this.validateHeaders(envelope);
            List<QName> inputNames = this.elements(body).stream() //
                    .map(element -> new QName(this.namespace(element), element.getLocalName())).toList();
            String action = this.incomingAction(input, mediaParameters);
            SoapDefinition definition = resource.definition();
            SoapOperation operation = definition.resolve(inputNames, action);
            if (operation == null) {
                operation = definition.resolve(inputNames, null);
            }
            if (operation == null) {
                throw new MetException(ProtocolStatus.NotFound, "SOAP operation not found: " + inputNames);
            }
            if (this.version == SoapVersion.SOAP_11) {
                this.validateAction(input.headers().get("SOAPAction"), operation);
            } else {
                this.validateMediaAction(mediaParameters.get("action"), operation);
            }

            Method method = this.method(service, operation);
            RequestPayload request = this.request(input, service, operation, method, requestId, requestTimeout);
            this.readHeaders(envelope, request, true, resource, operation);
            this.validateBody(resource, body, operation, operation.inputBody());
            Object[] values = this.bodies.read(body, operation.inputBody(), method.getGenericParameterTypes());
            Class<?>[] types = method.getParameterTypes();
            for (int index = 0; index < values.length; index++) {
                request.addParameter(types[index].getName(), values[index]);
            }
            return SoapInboundResult.request(request, operation, service, resource);
        } catch (SoapFault failure) {
            throw failure;
        } catch (SoapProtocolFaultException failure) {
            throw failure;
        } catch (Exception failure) {
            throw SoapProtocolFaultException.body(this.version.clientFaultCode(), this.failureMessage(failure));
        }
    }

    private SoapInboundResult getResponse(HttpRequest input, MetBindInfo<?> service, //
            SoapDefinitionResource resource, String methodName, long requestId, int requestTimeout) throws Exception {
        // SOAP 1.2 的 GET 只读取显式声明的无参资源，沿用正常 Dispatcher 和响应编码。
        SoapOperation operation = resource.definition().operation(methodName);
        if (operation == null) {
            return SoapInboundResult.response(this.simple(404));
        }
        Method method = this.method(service, operation);
        if (!method.isAnnotationPresent(SoapGet.class)) {
            return SoapInboundResult.response(this.simple(405));
        }
        if (input.stream() != null || input.body().length != 0) {
            return SoapInboundResult.response(this.simple(400));
        }
        RequestPayload request = this.request(input, service, operation, method, requestId, requestTimeout);
        return SoapInboundResult.request(request, operation, service, resource);
    }

    public HttpRequest encode(RequestPayload request, MetBindInfo<?> service, //
            SoapDefinitionResource resource) throws Exception {
        SoapOperation operation = this.operation(resource, request.getTargetMethod());
        Method method = this.method(service, operation);
        if (request.getParameterValues().size() != method.getParameterCount()) {
            throw new IllegalArgumentException("SOAP argument count does not match Java method: " + method.getName());
        }

        // 调用模式在 Caller 过滤器执行前由服务配置和方法注解确定，协议层只校验契约一致性。
        if (request.isMessage() != operation.oneWay()) {
            throw new IllegalArgumentException("SOAP request mode does not match WSDL operation: " + method.getName());
        }
        if (StringUtils.isBlank(request.getSerializeType())) {
            request.setSerializeType("Xml");
        }

        String alias = service.getAliasName("soap");
        String path = this.requestPath(service, alias);
        Map<String, List<String>> headers = this.requestHeaders(request);
        if (this.version == SoapVersion.SOAP_12 && method.isAnnotationPresent(SoapGet.class)) {
            headers.put("Accept", List.of(this.version.contentType()));
            String query = path + "?method=" + method.getName();
            return HttpRequest.newBuffered("GET", query, headers, new byte[0], 1, Map.of());
        }

        Document document = this.newDocument();
        Element body = this.envelope(document);
        Map<Element, byte[]> binaries = new IdentityHashMap<>();
        Object[] arguments = request.getParameterValues().toArray();
        this.bodies.write(body, operation.inputBody(), method.getGenericParameterTypes(), arguments, binaries);
        this.validateBody(resource, body, operation, operation.inputBody());
        this.writeHeaders(document, request, true, resource, operation);
        if (this.version == SoapVersion.SOAP_11) {
            headers.put("SOAPAction", List.of(this.quotedAction(operation.soapAction())));
        } else if (StringUtils.isNotBlank(operation.soapAction())) {
            this.validateMediaAction(operation.soapAction(), operation);
        }
        SoapAttachmentCodec.Message message = this.message(document, binaries, operation.soapAction());
        headers.put("Content-Type", List.of(message.contentType()));
        return HttpRequest.newBuffered("POST", path, headers, message.body(), 1, Map.of());
    }

    public HttpResponse encode(HttpExchange exchange, RequestPayload request, SoapOperation operation, //
            ResponsePayload response, MetBindInfo<?> service, SoapDefinitionResource resource) throws Exception {
        if (response.getFailure() != null || response.getStatus() != ProtocolStatus.OK) {
            return this.fault(response, this.version.serverFaultCode(), resource, operation);
        }

        if (operation.oneWay()) {
            throw new IllegalStateException("SOAP one-way operation cannot write a business response");
        }

        Document document = this.newDocument();
        Element body = this.envelope(document);
        Method method = this.method(service, operation);
        SoapResultMapping results = new SoapResultMapping(//
                operation.outputBody(), method.getGenericReturnType(), this.mapper);
        Map<Element, byte[]> binaries = new IdentityHashMap<>();
        Object[] values = results.split(response.getReturnData());
        this.bodies.write(body, operation.outputBody(), results.types(), values, binaries);
        this.validateBody(resource, body, operation, operation.outputBody());
        this.rpcResult(body, operation);
        this.writeHeaders(document, response, false, resource, operation);

        Map<String, List<String>> headers = this.responseHeaders(response);
        SoapAttachmentCodec.Message message = this.message(document, binaries, null);
        headers.put("Content-Type", List.of(message.contentType()));
        if (StringUtils.equalsIgnoreCase(exchange.request().method(), "GET")) {
            headers.putIfAbsent("Cache-Control", List.of("no-store"));
        }
        return HttpResponse.newBuffered(200, headers, message.body(), Map.of());
    }

    public ResponsePayload decode(RequestPayload request, HttpResponse input, MetBindInfo<?> service, //
            SoapDefinitionResource resource) throws Exception {
        SoapOperation operation = this.operation(resource, request.getTargetMethod());
        if (input.stream() != null) {
            throw new IllegalArgumentException("SOAP does not support an incremental HTTP response body");
        }
        byte[] responseBody = input.body();
        if (responseBody.length > this.config.integer("maxMessageSize", 4 * 1024 * 1024)) {
            throw new IllegalArgumentException("SOAP response exceeds maxMessageSize");
        }
        if (responseBody.length == 0) {
            boolean acceptedStatus = input.status() == 200 || input.status() == 202 || input.status() == 204;
            if (operation.oneWay() && acceptedStatus) {
                ResponsePayload accepted = this.response(request, ProtocolStatus.Accept, null, null);
                return this.copyResponseHeaders(input, accepted);
            }
            throw new IllegalArgumentException("SOAP HTTP " + input.status() + " response has no envelope");
        }

        String contentType = this.singleHeader(input.headers(), "Content-Type");
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.decode(responseBody, contentType, //
                this.version, this.maxMessageSize());
        Map<String, String> mediaParameters = this.mediaParameters(message.contentType());
        Charset charset = this.soapCharset(mediaParameters);
        Document document = this.parse(message.body(), charset);
        Element envelope = document.getDocumentElement();
        Element body = this.validateEnvelope(envelope);
        this.validateHeaders(envelope);
        ResponsePayload response = this.response(request, ProtocolStatus.OK, null, null);
        this.readHeaders(envelope, response, false, resource, operation);
        Element content = this.firstElement(body);
        if (content != null && StringUtils.equals(this.version.namespace(), this.namespace(content)) //
                && StringUtils.equals("Fault", content.getLocalName())) {
            if (this.elements(body).size() != 1) {
                throw new IllegalArgumentException("SOAP Fault must be the only Body element");
            }
            return this.copyResponseHeaders(input, this.decodeFault(request, content, resource, operation));
        }
        if (operation.oneWay()) {
            String error = "SOAP one-way operation must return an empty HTTP 200, 202 or 204 response";
            throw new IllegalArgumentException(error);
        }
        if (input.status() != 200) {
            ResponsePayload failure = this.response(request, ProtocolStatus.InvokeError, null, //
                    "HTTP status " + input.status());
            return this.copyResponseHeaders(input, failure);
        }

        Method method = this.method(service, operation);
        this.validateRpcResult(body, operation);
        this.validateBody(resource, body, operation, operation.outputBody());
        SoapResultMapping results = new SoapResultMapping(//
                operation.outputBody(), method.getGenericReturnType(), this.mapper);
        Object[] values = this.bodies.read(body, operation.outputBody(), results.types());
        response.setReturnData(results.join(values));
        return this.copyResponseHeaders(input, response);
    }

    public HttpResponse accepted() {
        return HttpResponse.newBuffered(202, Map.of("Content-Length", List.of("0")), new byte[0], Map.of());
    }

    public HttpResponse error(Throwable failure) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(failure instanceof MetException met ? met.getStatus() : ProtocolStatus.InvokeError);
        response.setFailure(failure);
        response.addOption("message", failure.getMessage());
        try {
            String faultCode = this.version.serverFaultCode();
            if (failure instanceof SoapProtocolFaultException fault) {
                faultCode = fault.faultCode();
            }
            return this.fault(response, faultCode);
        } catch (Exception suppressed) {
            return this.simple(500);
        }
    }

    private RequestPayload request(HttpRequest input, MetBindInfo<?> service, SoapOperation operation, //
            Method method, long requestId, int requestTimeout) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(requestId);
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType("Xml");
        request.setClientTimeout(requestTimeout);
        request.setReceiveTime(System.currentTimeMillis());
        request.setMessage(operation.oneWay());

        // HTTP 字段作为调用选项交给服务；同名字段保留首个值以符合现有 OptionInfo 契约。
        for (Map.Entry<String, List<String>> header : input.headers().entrySet()) {
            if (!header.getValue().isEmpty()) {
                request.addOption(header.getKey(), header.getValue().get(0));
            }
        }
        request.addOption("Location", input.uri());

        return request;
    }

    private HttpResponse fault(ResponsePayload response, String faultCode) throws Exception {
        return this.fault(response, faultCode, null, null);
    }

    private HttpResponse fault(ResponsePayload response, String faultCode, //
            SoapDefinitionResource resource, SoapOperation operation) throws Exception {
        Throwable failure = response.getFailure();
        SoapFault fault;
        if (failure instanceof SoapFault declared) {
            fault = declared;
        } else {
            short status = this.failureStatus(response.getStatus());
            String message = response.getOption("message");
            if (StringUtils.isBlank(message) && failure != null) {
                message = failure.getMessage();
            }
            if (StringUtils.isBlank(message)) {
                message = "SOAP invocation failed";
            }
            QName code = new QName(this.version.namespace(), faultCode);
            fault = SoapFault.create(status, code, message);
            if (!(failure instanceof SoapProtocolFaultException protocolFailure) || protocolFailure.bodyFailure()) {
                Document detailDocument = this.newDocument();
                Element detail = detailDocument.createElement("detail");
                this.text(detailDocument, detail, METEOR_NAMESPACE, "meteor:status", Short.toString(status));
                this.text(detailDocument, detail, METEOR_NAMESPACE, "meteor:message", message);
                fault.detail(detail);
            }
        }

        this.validateFault(fault);
        Document document = this.newDocument();
        Element body = this.envelope(document);
        Element faultElement = document.createElementNS(this.version.namespace(), "soap:Fault");
        body.appendChild(faultElement);
        if (this.version == SoapVersion.SOAP_11) {
            this.writeFault11(document, faultElement, fault);
        } else {
            this.writeFault12(document, faultElement, fault);
            SoapProtocolFaultException protocolFailure = null;
            if (failure instanceof SoapProtocolFaultException value) {
                protocolFailure = value;
            }
            this.faultHeader(document, body, protocolFailure, fault.getCode());
        }
        this.validateFaultBinding(faultElement, resource, operation);
        this.writeHeaders(document, response, false, resource, operation);

        Map<String, List<String>> headers = this.responseHeaders(response);
        headers.put("Content-Type", List.of(this.version.contentType() + "; charset=utf-8"));
        boolean sender = this.version == SoapVersion.SOAP_12 //
                && StringUtils.equals("Sender", fault.getCode().getLocalPart());
        return HttpResponse.newBuffered(sender ? 400 : 500, headers, this.bytes(document), Map.of());
    }

    private void writeFault11(Document document, Element target, SoapFault fault) {
        this.writeFaultQName(document, target, null, "faultcode", fault.getCode());
        SoapFault.Reason reason = fault.getReasons().get(0);
        Element faultString = document.createElement("faultstring");
        faultString.setTextContent(reason.text());
        if (reason.language() != null) {
            faultString.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", reason.language());
        }
        target.appendChild(faultString);
        if (fault.getActor() != null) {
            this.text(document, target, null, "faultactor", fault.getActor());
        }
        this.writeFaultDetail(document, target, fault.getDetail());
        for (Element extension : fault.getExtensions()) {
            target.appendChild(document.importNode(extension, true));
        }
    }

    private void writeFault12(Document document, Element target, SoapFault fault) {
        Element code = document.createElementNS(this.version.namespace(), "soap:Code");
        target.appendChild(code);
        this.writeFaultQName(document, code, this.version.namespace(), "soap:Value", fault.getCode());
        for (QName subcode : fault.getSubcodes()) {
            Element next = document.createElementNS(this.version.namespace(), "soap:Subcode");
            code.appendChild(next);
            this.writeFaultQName(document, next, this.version.namespace(), "soap:Value", subcode);
            code = next;
        }
        Element reason = document.createElementNS(this.version.namespace(), "soap:Reason");
        target.appendChild(reason);
        for (SoapFault.Reason value : fault.getReasons()) {
            Element text = document.createElementNS(this.version.namespace(), "soap:Text");
            text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", value.language());
            text.setTextContent(value.text());
            reason.appendChild(text);
        }
        if (fault.getNode() != null) {
            this.text(document, target, this.version.namespace(), "soap:Node", fault.getNode());
        }
        if (fault.getRole() != null) {
            this.text(document, target, this.version.namespace(), "soap:Role", fault.getRole());
        }
        this.writeFaultDetail(document, target, fault.getDetail());
    }

    private void writeFaultDetail(Document document, Element target, Element source) {
        if (source == null) {
            return;
        }
        Element detail = (Element) document.importNode(source, true);
        if (this.version == SoapVersion.SOAP_11) {
            detail = (Element) document.renameNode(detail, null, "detail");
        } else {
            detail = (Element) document.renameNode(detail, this.version.namespace(), "soap:Detail");
        }
        target.appendChild(detail);
    }

    private void writeFaultQName(Document document, Element parent, String namespace, String name, QName value) {
        Element element = namespace == null ? document.createElement(name) : document.createElementNS(namespace, name);
        if (StringUtils.isBlank(value.getNamespaceURI())) {
            element.setTextContent(value.getLocalPart());
            element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "");
        } else {
            String prefix = StringUtils.equals(this.version.namespace(), value.getNamespaceURI()) ? "soap" : "fault";
            element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, value.getNamespaceURI());
            element.setTextContent(prefix + ":" + value.getLocalPart());
        }
        parent.appendChild(element);
    }

    private void faultHeader(Document document, Element body, SoapProtocolFaultException failure, QName faultCode) {
        Element header = document.createElementNS(this.version.namespace(), "soap:Header");
        if (failure != null && failure.notUnderstood() != null) {
            QName name = failure.notUnderstood();
            Element entry = document.createElementNS(this.version.namespace(), "soap:NotUnderstood");
            entry.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:unknown", name.getNamespaceURI());
            entry.setAttribute("qname", "unknown:" + name.getLocalPart());
            header.appendChild(entry);
        } else if (StringUtils.equals("VersionMismatch", faultCode.getLocalPart())) {
            Element upgrade = document.createElementNS(this.version.namespace(), "soap:Upgrade");
            Element supported = document.createElementNS(this.version.namespace(), "soap:SupportedEnvelope");
            supported.setAttribute("qname", "soap:Envelope");
            upgrade.appendChild(supported);
            header.appendChild(upgrade);
        } else {
            return;
        }
        document.getDocumentElement().insertBefore(header, body);
    }

    private ResponsePayload decodeFault(RequestPayload request, Element fault, //
            SoapDefinitionResource resource, SoapOperation operation) throws Exception {
        this.validateFaultStructure(fault);
        List<QName> codes = new ArrayList<>();
        List<SoapFault.Reason> reasons = new ArrayList<>();
        Element detail;
        String actor = null;
        String node = null;
        String role = null;
        if (this.version == SoapVersion.SOAP_11) {
            Element code = this.child(fault, new QName("", "faultcode"));
            Element reason = this.child(fault, new QName("", "faultstring"));
            if (code == null || reason == null) {
                throw new IllegalArgumentException("SOAP Fault requires faultcode and faultstring");
            }
            codes.add(this.faultQName(code));
            String language = null;
            if (reason.hasAttributeNS(XMLConstants.XML_NS_URI, "lang")) {
                language = reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang");
            }
            reasons.add(new SoapFault.Reason(language, this.faultText(reason)));
            actor = this.faultUri(this.child(fault, new QName("", "faultactor")));
            detail = this.child(fault, new QName("", "detail"));
        } else {
            Element code = this.child(fault, new QName(this.version.namespace(), "Code"));
            Element reason = this.child(fault, new QName(this.version.namespace(), "Reason"));
            if (code == null || reason == null) {
                throw new IllegalArgumentException("SOAP 1.2 Fault requires Code/Value and Reason");
            }
            while (code != null) {
                this.validateFaultCodeStructure(code);
                Element value = this.child(code, new QName(this.version.namespace(), "Value"));
                if (value == null) {
                    throw new IllegalArgumentException("SOAP 1.2 Fault requires Code/Value and Reason");
                }
                codes.add(this.faultQName(value));
                code = this.child(code, new QName(this.version.namespace(), "Subcode"));
            }
            this.validateStructuralContent(reason);
            for (Element text : this.elements(reason)) {
                this.requireName(text, this.version.namespace(), "Text", "SOAP Reason must contain Text");
                String language = text.getAttributeNS(XMLConstants.XML_NS_URI, "lang");
                if (StringUtils.isBlank(language)) {
                    throw new IllegalArgumentException("SOAP Reason Text requires xml:lang");
                }
                reasons.add(new SoapFault.Reason(language, this.faultText(text)));
            }
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("SOAP 1.2 Fault Reason has no Text");
            }
            node = this.faultUri(this.child(fault, new QName(this.version.namespace(), "Node")));
            role = this.faultUri(this.child(fault, new QName(this.version.namespace(), "Role")));
            detail = this.child(fault, new QName(this.version.namespace(), "Detail"));
        }
        short status = ProtocolStatus.InvokeError;
        String message = reasons.get(0).text();
        if (detail != null) {
            this.validateStructuralContent(detail);
            String statusValue = this.text(this.firstElement(detail, METEOR_NAMESPACE, "status"));
            String detailedMessage = this.text(this.firstElement(detail, METEOR_NAMESPACE, "message"));
            if (StringUtils.isNotBlank(statusValue)) {
                try {
                    status = Short.parseShort(statusValue);
                } catch (NumberFormatException ignored) {
                    status = ProtocolStatus.InvokeError;
                }
            }
            status = this.failureStatus(status);
            if (StringUtils.isNotBlank(detailedMessage)) {
                message = detailedMessage;
            }
        }
        SoapFault decoded = SoapFault.create(status, codes.get(0), message, reasons) //
                .node(node).role(role).actor(actor).detail(detail);
        for (int index = 1; index < codes.size(); index++) {
            decoded.addSubcode(codes.get(index));
        }
        if (this.version == SoapVersion.SOAP_11) {
            for (Element child : this.elements(fault)) {
                if (StringUtils.isNotBlank(this.namespace(child))) {
                    decoded.addExtension(child);
                }
            }
        }
        this.validateFault(decoded);
        this.validateFaultBinding(fault, resource, operation);
        throw decoded;
    }

    private short failureStatus(short status) {
        return status == ProtocolStatus.OK || status == ProtocolStatus.Accept ? ProtocolStatus.InvokeError : status;
    }

    private void validateFault(SoapFault fault) {
        this.validateQName(fault.getCode());
        if (fault.getReasons().isEmpty()) {
            throw new IllegalArgumentException("SOAP Fault requires a reason");
        }
        if (this.version == SoapVersion.SOAP_12) {
            Set<String> codes = Set.of("VersionMismatch", "MustUnderstand", //
                    "DataEncodingUnknown", "Sender", "Receiver");
            boolean namespace = StringUtils.equals(this.version.namespace(), fault.getCode().getNamespaceURI());
            if (!namespace || !codes.contains(fault.getCode().getLocalPart())) {
                throw new IllegalArgumentException("Invalid SOAP 1.2 Fault Code: " + fault.getCode());
            }
            if (fault.getActor() != null || !fault.getExtensions().isEmpty()) {
                throw new IllegalArgumentException("SOAP 1.2 Fault cannot contain faultactor or SOAP 1.1 extensions");
            }
            for (QName subcode : fault.getSubcodes()) {
                this.validateQName(subcode);
            }
            for (SoapFault.Reason reason : fault.getReasons()) {
                if (StringUtils.isBlank(reason.language())) {
                    throw new IllegalArgumentException("SOAP Reason Text requires xml:lang");
                }
            }
        } else {
            boolean extendedCodeOrReason = !fault.getSubcodes().isEmpty() || fault.getReasons().size() != 1;
            boolean nodeOrRole = fault.getNode() != null || fault.getRole() != null;
            if (extendedCodeOrReason || nodeOrRole) {
                throw new IllegalArgumentException("SOAP 1.1 Fault cannot contain SOAP 1.2 fields");
            }
            for (Element extension : fault.getExtensions()) {
                if (StringUtils.isBlank(this.namespace(extension))) {
                    throw new IllegalArgumentException("SOAP Fault extensions must be namespace qualified");
                }
            }
        }
        this.validateUri(fault.getActor());
        this.validateUri(fault.getNode());
        this.validateUri(fault.getRole());
        if (fault.getDetail() != null) {
            this.validateStructuralContent(fault.getDetail());
        }
    }

    private void validateFaultStructure(Element fault) {
        this.validateStructuralContent(fault);
        List<String> order = List.of("Code", "Reason", "Node", "Role", "Detail");
        Set<String> names = new HashSet<>();
        int previous = -1;
        for (Element child : this.elements(fault)) {
            String namespace = this.namespace(child);
            String name = child.getLocalName();
            if (this.version == SoapVersion.SOAP_11) {
                if (StringUtils.isNotBlank(namespace)) {
                    continue;
                }
                if (!Set.of("faultcode", "faultstring", "faultactor", "detail").contains(name) || !names.add(name)) {
                    throw new IllegalArgumentException("Invalid or duplicate SOAP 1.1 Fault element: " + name);
                }
            } else {
                int position = order.indexOf(name);
                if (!StringUtils.equals(this.version.namespace(), namespace) || position <= previous) {
                    throw new IllegalArgumentException("Invalid SOAP 1.2 Fault element order: " + name);
                }
                previous = position;
            }
        }
    }

    private void validateFaultCodeStructure(Element code) {
        this.validateStructuralContent(code);
        List<Element> children = this.elements(code);
        if (children.isEmpty() || children.size() > 2) {
            throw new IllegalArgumentException("SOAP Fault Code/Subcode requires Value and optional Subcode");
        }
        this.requireName(children.get(0), this.version.namespace(), "Value", "SOAP Fault Code must start with Value");
        if (children.size() == 2) {
            String message = "SOAP Fault Value must precede Subcode";
            this.requireName(children.get(1), this.version.namespace(), "Subcode", message);
        }
    }

    private String faultText(Element element) {
        this.validateEncodingPosition(element);
        if (!this.elements(element).isEmpty()) {
            throw new IllegalArgumentException("SOAP Fault " + element.getLocalName() + " must contain text only");
        }
        return element.getTextContent();
    }

    private String faultUri(Element element) {
        if (element == null) {
            return null;
        }
        String value = this.faultText(element).trim();
        this.validateUri(value);
        return value;
    }

    private void validateUri(String value) {
        if (value != null) {
            URI.create(value);
        }
    }

    private QName faultQName(Element element) {
        this.faultText(element);
        try {
            return this.xmlQName(element);
        } catch (IllegalArgumentException failure) {
            String message = "Invalid SOAP Fault QName: " + element.getTextContent().trim();
            throw new IllegalArgumentException(message, failure);
        }
    }

    private QName xmlQName(Element element) {
        if (!this.elements(element).isEmpty()) {
            throw new IllegalArgumentException("SOAP QName value must contain text only");
        }
        String value = element.getTextContent().trim();
        int separator = value.indexOf(':');
        String prefix = separator < 0 ? null : value.substring(0, separator);
        String localName = separator < 0 ? value : value.substring(separator + 1);
        String namespace = element.lookupNamespaceURI(prefix);
        boolean invalidName = StringUtils.isBlank(localName) || localName.indexOf(':') >= 0;
        boolean invalidPrefix = separator == 0 || separator >= 0 && namespace == null;
        if (invalidName || invalidPrefix) {
            throw new IllegalArgumentException("Invalid SOAP QName: " + value);
        }
        try {
            element.getOwnerDocument().createElementNS(namespace, value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid SOAP QName: " + value, failure);
        }
        return new QName(namespace == null ? "" : namespace, localName);
    }

    private void validateQName(QName name) {
        try {
            String qualifiedName = name.getLocalPart();
            if (StringUtils.isNotBlank(name.getNamespaceURI())) {
                qualifiedName = "q:" + qualifiedName;
            }
            this.newDocument().createElementNS(name.getNamespaceURI(), qualifiedName);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid SOAP Fault QName: " + name, failure);
        }
    }

    private ResponsePayload response(RequestPayload request, short status, Object value, String message) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setReceiveTime(System.currentTimeMillis());
        response.setSerializeType("Xml");
        response.setStatus(status);
        if (value != null) {
            response.setReturnType(value.getClass().getName());
            response.setReturnData(value);
        }
        if (StringUtils.isNotBlank(message)) {
            response.addOption("message", message);
        }
        return response;
    }

    private SoapOperation operation(SoapDefinitionResource resource, String methodName) {
        SoapOperation operation = resource.definition().operation(methodName);
        if (operation == null) {
            throw new IllegalArgumentException("WSDL operation not found: " + methodName);
        }
        return operation;
    }

    private Method method(MetBindInfo<?> service, SoapOperation operation) {
        Method selected = null;
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (!StringUtils.equals(method.getName(), operation.name())) {
                continue;
            }
            if (selected != null) {
                String message = "SOAP service does not support overloaded method: " + method.getName();
                throw new IllegalArgumentException(message);
            }
            selected = method;
        }
        if (selected == null) {
            throw new IllegalArgumentException("Java method not found for WSDL operation: " + operation.name());
        }
        return selected;
    }

    private int maxMessageSize() {
        return this.config.integer("maxMessageSize", 4 * 1024 * 1024);
    }

    private void validateBody(SoapDefinitionResource resource, Element body, //
            SoapOperation operation, SoapBody binding) throws Exception {
        if (StringUtils.equals("document", operation.style()) && !binding.encoded()) {
            resource.validateBody(body, binding);
        }
    }

    private SoapAttachmentCodec.Message message(Document document, //
            Map<Element, byte[]> binaries, String action) throws Exception {
        String mode = this.config.option("attachmentMode", "inline");
        boolean inline = StringUtils.equalsIgnoreCase("inline", mode);
        boolean mtom = StringUtils.equalsIgnoreCase("mtom", mode);
        if (!inline && !mtom && !StringUtils.equalsIgnoreCase("swa", mode)) {
            throw new IllegalArgumentException("SOAP attachmentMode must be inline, mtom or swa");
        }
        int threshold = Integer.parseInt(this.config.option("attachmentThreshold", "1024"));
        if (threshold < 0) {
            throw new IllegalArgumentException("SOAP attachmentThreshold must not be negative");
        }
        Map<Element, byte[]> selected = new IdentityHashMap<>();
        if (!inline) {
            for (Map.Entry<Element, byte[]> binary : binaries.entrySet()) {
                if (binary.getValue().length >= threshold) {
                    selected.put(binary.getKey(), binary.getValue());
                }
            }
        }
        return SoapAttachmentCodec.encode(document, selected, this.version, action, mtom, this.maxMessageSize());
    }

    private String incomingAction(HttpRequest request, Map<String, String> mediaParameters) {
        if (this.version == SoapVersion.SOAP_12) {
            return mediaParameters.get("action");
        }
        String action = this.singleHeader(request.headers(), "SOAPAction").trim();
        if (action.length() >= 2 && action.startsWith("\"") && action.endsWith("\"")) {
            return action.substring(1, action.length() - 1);
        }
        return action;
    }

    private void rpcResult(Element body, SoapOperation operation) {
        boolean rpc12 = this.version == SoapVersion.SOAP_12 && StringUtils.equals("rpc", operation.style());
        if (!rpc12 || !operation.outputBody().encoded() || operation.outputBody().parts().isEmpty()) {
            return;
        }
        Element wrapper = this.firstElement(body);
        Element result = body.getOwnerDocument().createElementNS("http://www.w3.org/2003/05/soap-rpc", "rpc:result");
        QName name = operation.outputBody().parts().get(0).element();
        String value = name.getLocalPart();
        if (StringUtils.isNotBlank(name.getNamespaceURI())) {
            result.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:result", name.getNamespaceURI());
            value = "result:" + value;
        } else {
            result.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "");
        }
        result.setTextContent(value);
        wrapper.insertBefore(result, wrapper.getFirstChild());
    }

    private void validateRpcResult(Element body, SoapOperation operation) {
        boolean rpc12 = this.version == SoapVersion.SOAP_12 && StringUtils.equals("rpc", operation.style());
        if (!rpc12 || !operation.outputBody().encoded() || operation.outputBody().parts().isEmpty()) {
            return;
        }
        Element wrapper = this.firstElement(body);
        QName resultName = new QName("http://www.w3.org/2003/05/soap-rpc", "result");
        Element result = this.child(wrapper, resultName);
        if (result == null) {
            throw new IllegalArgumentException("SOAP 1.2 RPC response requires rpc:result");
        }
        QName expected = operation.outputBody().parts().get(0).element();
        if (!expected.equals(this.xmlQName(result))) {
            throw new IllegalArgumentException("SOAP 1.2 RPC result does not identify the return accessor");
        }
        wrapper.removeChild(result);
    }

    private void validateAction(List<String> values, SoapOperation operation) {
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("SOAPAction header must occur exactly once");
        }
        String value = values.get(0);
        String action = value.trim();
        if (action.length() >= 2 && action.startsWith("\"") && action.endsWith("\"")) {
            action = action.substring(1, action.length() - 1);
        }
        String expected = operation.soapAction() == null ? "" : operation.soapAction();
        if (!StringUtils.equals(action, expected)) {
            throw new IllegalArgumentException("SOAPAction does not match operation: " + action);
        }
    }

    private String quotedAction(String action) {
        String value = action == null ? "" : action;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private String requestPath(MetBindInfo<?> service, String alias) {
        Set<String> exact = this.config.exactPaths(service);
        if (!exact.isEmpty()) {
            return exact.iterator().next();
        }
        return this.config.requestPath(service, alias);
    }

    private Map<String, List<String>> requestHeaders(RequestPayload request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : request.getOptionKeys()) {
            String value = key == null ? null : request.getOption(key);
            if (this.transferHeader(key, value)) {
                headers.put(key, List.of(value));
            }
        }
        return headers;
    }

    private Map<String, List<String>> responseHeaders(ResponsePayload response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : response.getOptionKeys()) {
            String value = key == null ? null : response.getOption(key);
            if (this.transferHeader(key, value)) {
                headers.put(key, List.of(value));
            }
        }
        return headers;
    }

    private ResponsePayload copyResponseHeaders(HttpResponse input, ResponsePayload response) {
        for (Map.Entry<String, List<String>> header : input.headers().entrySet()) {
            String name = header.getKey();
            if (StringUtils.isBlank(name) || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> values = header.getValue();
            if (values.isEmpty()) {
                continue;
            }
            String value = values.get(0);
            if (this.safeHeaderName(name) && this.safeHeaderValue(value)) {
                response.addOption(name, value);
            }
        }
        return response;
    }

    private boolean transferHeader(String name, String value) {
        return StringUtils.isNotBlank(name) //
                && !RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) //
                && this.safeHeaderName(name) //
                && this.safeHeaderValue(value);
    }

    private String singleHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException(name + " header must occur exactly once");
        }
        return values.get(0);
    }

    private Map<String, String> mediaParameters(String contentType) {
        List<String> parts = this.mimeParts(contentType);
        String expectedType = this.version.contentType();
        if (parts.isEmpty() || !StringUtils.equalsIgnoreCase(expectedType, parts.get(0).trim())) {
            String message = "SOAP " + this.version.displayName() + " Content-Type must be " + expectedType;
            throw new IllegalArgumentException(message);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 1; index < parts.size(); index++) {
            String parameter = parts.get(index).trim();
            if (parameter.isEmpty()) {
                continue;
            }
            int separator = parameter.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed SOAP Content-Type parameter: " + parameter);
            }
            String name = parameter.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = this.parameterValue(parameter.substring(separator + 1));
            if (parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("SOAP Content-Type declares " + name + " more than once");
            }
        }
        return parameters;
    }

    private Charset soapCharset(Map<String, String> parameters) {
        String charset = parameters.get("charset");
        return charset == null ? null : Charset.forName(charset);
    }

    private void validateMediaAction(String action, SoapOperation operation) {
        // SOAP 1.2 action 的必选性由 WSDL 决定，SOAPAction 不替代媒体类型参数。
        if (action == null) {
            if (operation.actionRequired()) {
                throw new IllegalArgumentException("SOAP 1.2 operation requires a Content-Type action parameter");
            }
            return;
        }
        if (StringUtils.isBlank(action) || !URI.create(action).isAbsolute()) {
            throw new IllegalArgumentException("SOAP 1.2 action must be a non-empty absolute URI");
        }
        if (!StringUtils.equals(action, operation.soapAction())) {
            throw new IllegalArgumentException("SOAP 1.2 action does not match operation: " + action);
        }
    }

    private List<String> mimeParts(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (quoted && character == '\\') {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                current.append(character);
                quoted = !quoted;
            } else if (character == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("Malformed quoted SOAP Content-Type parameter");
        }
        parts.add(current.toString());
        return parts;
    }

    private String parameterValue(String source) {
        String value = source.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        } else if (value.startsWith("\"") || value.endsWith("\"")) {
            throw new IllegalArgumentException("Malformed quoted SOAP Content-Type parameter");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SOAP Content-Type parameter must not be blank");
        }
        return value;
    }

    private boolean safeHeaderName(String name) {
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean alphaNumeric = character >= 'a' && character <= 'z' //
                    || character >= 'A' && character <= 'Z' //
                    || character >= '0' && character <= '9';
            boolean punctuation = "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
            if (!alphaNumeric && !punctuation) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    private boolean safeHeaderValue(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\t' && (character < 0x20 || character == 0x7F || character > 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private HttpResponse methodNotAllowed() {
        return HttpResponse.newBuffered(405, Map.of("Allow", List.of("GET, HEAD, POST")), new byte[0], Map.of());
    }

    private HttpResponse simple(int status) {
        return HttpResponse.newBuffered(status, Map.of(), new byte[0], Map.of());
    }

    private Document newDocument() throws Exception {
        return this.documentFactory().newDocumentBuilder().newDocument();
    }

    private Document parse(byte[] bytes, Charset charset) throws Exception {
        InputSource source = new InputSource(new ByteArrayInputStream(bytes));
        if (charset != null) {
            source.setEncoding(charset.name());
        }
        Document document = this.documentFactory().newDocumentBuilder().parse(source);
        this.rejectProcessingInstructions(document);
        try {
            SoapXmlUtils.validateDocument(document, "SOAP message");
        } catch (IllegalArgumentException failure) {
            throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), failure.getMessage());
        }
        return document;
    }

    private void rejectProcessingInstructions(Node root) {
        Deque<Node> remaining = new ArrayDeque<>();
        remaining.add(root);
        while (!remaining.isEmpty()) {
            Node node = remaining.removeFirst();
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                String message = "SOAP must not contain processing instructions";
                throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
            }
            NodeList children = node.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                remaining.addLast(children.item(index));
            }
        }
    }

    private String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return StringUtils.isBlank(message) ? failure.getClass().getSimpleName() : message;
    }

    private DocumentBuilderFactory documentFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private Element envelope(Document document) {
        Element envelope = document.createElementNS(this.version.namespace(), "soap:Envelope");
        document.appendChild(envelope);
        Element body = document.createElementNS(this.version.namespace(), "soap:Body");
        envelope.appendChild(body);
        return body;
    }

    private void requireName(Element element, String namespace, String localName, String message) {
        if (!StringUtils.equals(namespace, this.namespace(element)) //
                || !StringUtils.equals(localName, element.getLocalName())) {
            throw new IllegalArgumentException(message);
        }
    }

    private Element validateEnvelope(Element envelope) {
        boolean soapNamespace = StringUtils.equals(this.version.namespace(), this.namespace(envelope));
        boolean envelopeName = StringUtils.equals("Envelope", envelope.getLocalName());
        if (!soapNamespace || !envelopeName) {
            String message = "Expected SOAP " + this.version.displayName() + " Envelope";
            throw SoapProtocolFaultException.envelope("VersionMismatch", message);
        }
        this.validateStructuralContent(envelope);
        Element body = null;
        Element header = null;
        for (Element child : this.elements(envelope)) {
            String namespace = this.namespace(child);
            boolean soap = StringUtils.equals(this.version.namespace(), namespace);
            boolean extension = this.version == SoapVersion.SOAP_11 && body != null;
            extension = extension && !soap && StringUtils.isNotBlank(namespace);
            if (soap && StringUtils.equals("Header", child.getLocalName()) && header == null && body == null) {
                header = child;
                this.validateStructuralContent(header);
            } else if (soap && StringUtils.equals("Body", child.getLocalName()) && body == null) {
                body = child;
                this.validateStructuralContent(body);
            } else if (extension) {
                // SOAP 1.1 允许 Body 后出现有命名空间的 Envelope 扩展。
                continue;
            } else {
                String message = "SOAP Envelope requires an optional Header followed by exactly one Body";
                throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
            }
        }
        if (body == null) {
            throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), "SOAP Envelope has no Body");
        }
        return body;
    }

    private void validateStructuralContent(Element element) {
        this.validateEncodingPosition(element);
        String localName = element.getLocalName();
        boolean headerOrBody = StringUtils.equals("Header", localName) || StringUtils.equals("Body", localName);
        boolean envelopePart = StringUtils.equals("Envelope", localName) || headerOrBody;
        if (this.version == SoapVersion.SOAP_12 && envelopePart) {
            NamedNodeMap attributes = element.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                if (StringUtils.isBlank(attribute.getNamespaceURI())) {
                    String message = "SOAP " + localName + " attributes must be namespace qualified";
                    throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
                }
            }
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            boolean text = node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE;
            if (text && !this.xmlWhitespace(node.getTextContent())) {
                String message = "SOAP " + localName + " cannot contain character data";
                throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
            }
        }
    }

    private boolean xmlWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
                return false;
            }
        }
        return true;
    }

    private void validateEncodingPosition(Element element) {
        if (this.version == SoapVersion.SOAP_12 && element.hasAttributeNS(this.version.namespace(), "encodingStyle")) {
            String message = "SOAP encodingStyle is not allowed on " + element.getLocalName();
            throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
        }
    }

    private void validateHeaders(Element envelope) {
        Element header = this.firstElement(envelope, this.version.namespace(), "Header");
        if (header == null) {
            return;
        }
        SoapHeaderHandler handler = this.headerHandler;
        Set<String> roles = this.headerRoles(handler);
        for (Element entry : this.elements(header)) {
            this.validateHeaderBlock(entry);
            if (handler == null && this.headerTargeted(entry, roles) && this.headerBoolean(entry, "mustUnderstand")) {
                throw SoapProtocolFaultException.notUnderstood(new QName(this.namespace(entry), entry.getLocalName()));
            }
        }
    }

    private void readHeaders(Element envelope, Payload message, boolean request, //
            SoapDefinitionResource resource, SoapOperation operation) throws Exception {
        Element header = this.firstElement(envelope, this.version.namespace(), "Header");
        if (header == null) {
            return;
        }
        SoapHeaderHandler handler = this.headerHandler;
        Set<String> roles = this.headerRoles(handler);
        this.validateHeaderBindings(header, request, resource, operation, roles);
        for (Element entry : this.elements(header)) {
            this.validateHeaderBlock(entry);
            if (!this.headerTargeted(entry, roles)) {
                continue;
            }
            boolean required = this.headerBoolean(entry, "mustUnderstand");
            boolean understood = handler != null && handler.read(entry, message, request);
            if (!understood && required) {
                throw SoapProtocolFaultException.notUnderstood(new QName(this.namespace(entry), entry.getLocalName()));
            }
        }
    }

    private void writeHeaders(Document document, Payload message, boolean request, //
            SoapDefinitionResource resource, SoapOperation operation) throws Exception {
        SoapHeaderHandler handler = this.headerHandler;
        if (handler == null) {
            return;
        }
        Element envelope = document.getDocumentElement();
        Element header = this.firstElement(envelope, this.version.namespace(), "Header");
        if (header == null) {
            header = document.createElementNS(this.version.namespace(), "soap:Header");
            envelope.insertBefore(header, envelope.getFirstChild());
        }
        handler.write(header, message, request);
        this.validateStructuralContent(header);
        for (Element entry : this.elements(header)) {
            this.validateHeaderBlock(entry);
        }
        this.validateHeaderBindings(header, request, resource, operation, null);
        if (this.elements(header).isEmpty()) {
            envelope.removeChild(header);
        }
    }

    private void validateHeaderBindings(Element header, boolean request, //
            SoapDefinitionResource resource, SoapOperation operation, Set<String> roles) {
        if (resource == null || operation == null) {
            return;
        }
        List<SoapHeaderBinding> declared = request ? operation.inputHeaders() : operation.outputHeaders();
        List<SoapHeaderBinding> bindings = new ArrayList<>(declared);
        if (!request) {
            for (SoapHeaderBinding input : operation.inputHeaders()) {
                bindings.addAll(input.faults());
            }
        }
        for (Element entry : this.elements(header)) {
            if (roles != null && !this.headerTargeted(entry, roles)) {
                continue;
            }
            QName name = new QName(this.namespace(entry), entry.getLocalName());
            for (SoapHeaderBinding binding : bindings) {
                if (!binding.part().element().equals(name)) {
                    continue;
                }
                try {
                    this.validateBoundPart(entry, binding.part(), binding.use(), binding.encodingStyle(), resource);
                } catch (SoapProtocolFaultException failure) {
                    throw failure;
                } catch (Exception failure) {
                    String message = "SOAP Header binding validation failed for " + name + ": " + failure.getMessage();
                    throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
                }
            }
        }
    }

    private void validateFaultBinding(Element fault, SoapDefinitionResource resource, //
            SoapOperation operation) throws Exception {
        if (resource == null || operation == null || operation.faults().isEmpty()) {
            return;
        }
        QName detailName;
        if (this.version == SoapVersion.SOAP_11) {
            detailName = new QName("", "detail");
        } else {
            detailName = new QName(this.version.namespace(), "Detail");
        }
        Element detail = this.child(fault, detailName);
        if (detail == null) {
            return;
        }
        SoapFaultBinding matched = null;
        for (Element entry : this.elements(detail)) {
            QName name = new QName(this.namespace(entry), entry.getLocalName());
            SoapFaultBinding entryBinding = null;
            for (SoapFaultBinding binding : operation.faults().values()) {
                if (!binding.part().element().equals(name)) {
                    continue;
                }
                if (matched != null) {
                    throw new IllegalArgumentException("SOAP Fault detail matches more than one declared fault part");
                }
                entryBinding = binding;
                try {
                    this.validateBoundPart(entry, binding.part(), binding.use(), binding.encodingStyle(), resource);
                } catch (Exception failure) {
                    String message = "SOAP Fault binding validation failed for " + name + ": " + failure.getMessage();
                    throw new IllegalArgumentException(message, failure);
                }
            }
            if (entryBinding != null) {
                matched = entryBinding;
            }
        }
        if (matched != null && StringUtils.equals("literal", matched.use()) && this.elements(detail).size() != 1) {
            throw new IllegalArgumentException("SOAP literal Fault detail must contain its single declared part");
        }
    }

    private void validateBoundPart(Element entry, SoapPart part, String use, //
            String encodingStyle, SoapDefinitionResource resource) throws Exception {
        this.validatePartEncoding(entry, encodingStyle);
        if (StringUtils.equals("literal", use)) {
            resource.validatePart(entry, part);
        } else {
            // Header 和 Fault 保留 DOM 表达；校验整个 Envelope 中的编码引用，允许前向引用。
            this.bodies.validateEncoded(entry.getOwnerDocument().getDocumentElement());
        }
    }

    private void validatePartEncoding(Element entry, String expected) {
        Element root = entry;
        while (!root.hasAttributeNS(this.version.namespace(), "encodingStyle")) {
            if (!(root.getParentNode() instanceof Element parent)) {
                root = null;
                break;
            }
            root = parent;
        }
        if (root != null) {
            this.validateEncodingValue(root.getAttributeNS(this.version.namespace(), "encodingStyle"), expected);
        }
        Deque<Element> remaining = new ArrayDeque<>();
        remaining.add(entry);
        while (!remaining.isEmpty()) {
            Element current = remaining.removeFirst();
            if (current.hasAttributeNS(this.version.namespace(), "encodingStyle")) {
                String value = current.getAttributeNS(this.version.namespace(), "encodingStyle");
                this.validateEncodingValue(value, expected);
            }
            remaining.addAll(this.elements(current));
        }
    }

    private void validateEncodingValue(String actual, String expected) {
        String value = actual.trim();
        String declared = expected == null ? "" : expected.trim();
        if (StringUtils.equals(value, declared)) {
            return;
        }
        boolean noClaim;
        String supported;
        if (this.version == SoapVersion.SOAP_11) {
            noClaim = value.isEmpty();
            supported = "http://schemas.xmlsoap.org/soap/encoding/";
        } else {
            noClaim = StringUtils.equals(value, this.version.namespace() + "/encoding/none");
            supported = "http://www.w3.org/2003/05/soap-encoding";
        }
        if (noClaim) {
            return;
        }
        List<String> encodings = value.isEmpty() ? List.of() : Arrays.asList(value.split("\\s+"));
        boolean supportedList = this.version == SoapVersion.SOAP_11 || encodings.size() == 1;
        boolean declaredEncoding = declared.isEmpty() || StringUtils.equals(declared, supported);
        if (supportedList && encodings.contains(supported) && declaredEncoding) {
            return;
        }
        String code = this.version == SoapVersion.SOAP_12 ? "DataEncodingUnknown" : this.version.clientFaultCode();
        String message = "SOAP part encodingStyle does not match its WSDL binding: " + actual;
        throw SoapProtocolFaultException.envelope(code, message);
    }

    private Set<String> headerRoles(SoapHeaderHandler handler) {
        Set<String> roles = new HashSet<>();
        if (handler != null) {
            Set<String> declared = Objects.requireNonNull(handler.roles(), "SOAP handler roles");
            for (String role : declared) {
                if (StringUtils.isBlank(role)) {
                    throw new IllegalArgumentException("SOAP handler role must be a non-empty URI");
                }
                this.validateUri(role);
                if (StringUtils.equals(role, SoapVersion.SOAP_12.namespace() + "/role/none")) {
                    throw new IllegalArgumentException("SOAP node must not assume the none role");
                }
                roles.add(role);
            }
        }
        if (this.version == SoapVersion.SOAP_11) {
            roles.add(SOAP_ACTOR_NEXT);
        } else {
            roles.add(this.version.namespace() + "/role/next");
            roles.add(this.version.namespace() + "/role/ultimateReceiver");
        }
        return roles;
    }

    private boolean headerTargeted(Element entry, Set<String> roles) {
        String attribute = this.version == SoapVersion.SOAP_11 ? "actor" : "role";
        if (!entry.hasAttributeNS(this.version.namespace(), attribute)) {
            return true;
        }
        String role = entry.getAttributeNS(this.version.namespace(), attribute).trim();
        return roles.contains(role);
    }

    private void validateHeaderBlock(Element entry) {
        if (StringUtils.isBlank(this.namespace(entry))) {
            String message = "SOAP header blocks must be namespace qualified";
            throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
        }
        this.headerBoolean(entry, "mustUnderstand");
        String roleAttribute = this.version == SoapVersion.SOAP_11 ? "actor" : "role";
        if (entry.hasAttributeNS(this.version.namespace(), roleAttribute)) {
            try {
                this.validateUri(entry.getAttributeNS(this.version.namespace(), roleAttribute).trim());
            } catch (IllegalArgumentException failure) {
                String message = "Invalid SOAP " + roleAttribute + " URI";
                throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
            }
        }
        if (this.version == SoapVersion.SOAP_12) {
            this.headerBoolean(entry, "relay");
        }
    }

    private boolean headerBoolean(Element entry, String attribute) {
        if (!entry.hasAttributeNS(this.version.namespace(), attribute)) {
            return false;
        }
        String value = entry.getAttributeNS(this.version.namespace(), attribute).trim();
        if (StringUtils.equals(value, "1")) {
            return true;
        }
        if (StringUtils.equals(value, "0")) {
            return false;
        }
        if (this.version == SoapVersion.SOAP_12) {
            if (StringUtils.equals(value, "true")) {
                return true;
            }
            if (StringUtils.equals(value, "false")) {
                return false;
            }
        }
        String message = "Invalid SOAP " + attribute + ": " + value;
        throw SoapProtocolFaultException.envelope(this.version.clientFaultCode(), message);
    }

    private Element firstElement(Element parent, String namespace, String name) {
        for (Element child : this.elements(parent)) {
            if (StringUtils.equals(namespace, this.namespace(child)) //
                    && StringUtils.equals(name, child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private Element firstElement(Element parent) {
        List<Element> children = this.elements(parent);
        return children.isEmpty() ? null : children.get(0);
    }

    private Element child(Element parent, String localName) {
        if (parent == null || localName == null) {
            return null;
        }
        for (Element child : this.elements(parent)) {
            if (StringUtils.equals(localName, child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private Element child(Element parent, QName name) {
        if (parent == null || name == null) {
            return null;
        }
        Element selected = null;
        for (Element child : this.elements(parent)) {
            boolean namespaceMatches = StringUtils.equals(name.getNamespaceURI(), this.namespace(child));
            if (namespaceMatches && StringUtils.equals(name.getLocalPart(), child.getLocalName())) {
                if (selected != null) {
                    throw new IllegalArgumentException("Duplicate SOAP element: " + name);
                }
                selected = child;
            }
        }
        return selected;
    }

    private List<Element> elements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private byte[] bytes(Node node) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(node), new StreamResult(output));
        return output.toByteArray();
    }

    private void text(Document document, Element parent, String namespace, String name, String value) {
        Element element = namespace == null ? document.createElement(name) : document.createElementNS(namespace, name);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private String text(Element element) {
        return element == null ? null : element.getTextContent();
    }

    private String namespace(Element element) {
        String namespace = element.getNamespaceURI();
        return namespace == null ? "" : namespace;
    }

    private String query(String uri) {
        int query = uri.indexOf('?');
        return query < 0 || query + 1 == uri.length() ? null : uri.substring(query + 1);
    }
}
