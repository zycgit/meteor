/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.net.ssl.SSLContext;
import com.sun.xml.ws.developer.JAXWSProperties;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Service;
import net.hasor.example.protocol.soap.MetroRpcEchoService;
import net.hasor.example.protocol.soap.metro.MetroMetXmlOrder;
import net.hasor.example.protocol.soap.metro.MetroMetXmlOrderService;

/** 官方 Metro 客户端的有限契约操作；控制输入与结果只包含 JDK 值和 XML 字节。 */
final class MetroPeerClient {
    private MetroPeerClient() {
    }

    static Map<String, Object> invoke(Map<String, Object> request) throws Exception {
        // WSDL 来自测试实际发布的网络地址或显式文件，端点按场景决定是否覆盖。
        URL wsdl = URI.create((String) request.get("wsdl")).toURL();
        QName serviceName = (QName) request.get("service");
        QName portName = (QName) request.get("port");
        Service service = Service.create(wsdl, serviceName);
        String operation = (String) request.get("operation");
        switch (operation) {
            case "dispatch": {
                Dispatch<SOAPMessage> dispatch = service.createDispatch(portName, SOAPMessage.class, //
                        Service.Mode.MESSAGE);
                configure(dispatch, request);
                String protocol = (String) request.get("soapProtocol");
                MessageFactory factory = MessageFactory.newInstance(protocol);
                byte[] body = (byte[]) request.get("request");
                SOAPMessage message = factory.createMessage(null, new ByteArrayInputStream(body));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("bindingId", dispatch.getBinding().getBindingID());
                if (Boolean.TRUE.equals(request.get("oneWay"))) {
                    dispatch.invokeOneWay(message);
                    return result;
                }
                SOAPMessage response = dispatch.invoke(message);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                response.writeTo(bytes);
                result.put("soapProtocol", protocol);
                result.put("response", bytes.toByteArray());
                return result;
            }
            case "typed-rpc": {
                MetroRpcEchoService remote = service.getPort(portName, MetroRpcEchoService.class);
                BindingProvider provider = (BindingProvider) remote;
                configure(provider, request);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("bindingId", provider.getBinding().getBindingID());
                result.put("value", remote.echo((String) request.get("value")));
                return result;
            }
            case "typed-order": {
                MetroMetXmlOrderService remote = service.getPort(portName, MetroMetXmlOrderService.class);
                configure((BindingProvider) remote, request);
                MetroMetXmlOrder input = order(request);
                MetroMetXmlOrder value = remote.echo(input);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", value.getId());
                result.put("language", value.getLanguage());
                result.put("buyer", value.getBuyer());
                result.put("delivery", value.getDelivery());
                result.put("lines", value.getLines());
                result.put("state", value.getState());
                result.put("memo", value.getMemo());
                return result;
            }
            default: {
                throw new IllegalArgumentException("Unknown Metro client operation: " + operation);
            }
        }
    }

    private static void configure(BindingProvider provider, Map<String, Object> request) throws Exception {
        // 超时和 TLS 由原生客户端自行配置，不继承 Meteor 的网络或安全对象。
        Map<String, Object> options = provider.getRequestContext();
        options.put(JAXWSProperties.CONNECT_TIMEOUT, 10000);
        options.put(JAXWSProperties.REQUEST_TIMEOUT, 10000);
        String endpoint = (String) request.get("endpoint");
        if (endpoint != null) {
            options.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint);
        }
        String action = (String) request.get("soapAction");
        if (action != null) {
            options.put(BindingProvider.SOAPACTION_USE_PROPERTY, true);
            options.put(BindingProvider.SOAPACTION_URI_PROPERTY, action);
        }
        String directory = (String) request.get("tlsDirectory");
        if (directory != null) {
            SSLContext context = SoapPeerFiles.sslContext(Path.of(directory), "client");
            options.put(JAXWSProperties.SSL_SOCKET_FACTORY, context.getSocketFactory());
        }
    }

    @SuppressWarnings("unchecked")
    private static MetroMetXmlOrder order(Map<String, Object> request) {
        // 只有官方 JAXB DTO 参与 SOAP 编解码；本地控制通道没有 DTO 类型依赖。
        Map<String, Object> values = (Map<String, Object>) request.get("order");
        MetroMetXmlOrder order = new MetroMetXmlOrder();
        order.setId((String) values.get("id"));
        order.setLanguage((String) values.get("language"));
        order.setBuyer((String) values.get("buyer"));
        order.setDelivery((String) values.get("delivery"));
        order.setLines((List<String>) values.get("lines"));
        order.setState((String) values.get("state"));
        order.setMemo((String) values.get("memo"));
        return order;
    }
}
