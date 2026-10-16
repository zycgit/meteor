/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.namespace.QName;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import jakarta.xml.soap.*;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.example.protocol.soap.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class WebServicesInteropTest {
    private static final String ECHO_NAMESPACE         = "urn:meteor:test:echo";
    private static final String ECHO_TYPES_NAMESPACE   = "urn:meteor:test:echo:types";
    private static final String SIGNAL_NAMESPACE       = "urn:meteor:test:signal";
    private static final String SIGNAL_TYPES_NAMESPACE = "urn:meteor:test:signal:types";

    @Test(timeout = 20000)
    public void metroDispatchInvokesRequestResponseFromPublishedWsdl() throws Exception {
        // 通过公开 Bootstrap API 配置并启动 SOAP 服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            URL resource = this.resource("/wsdl/echo.wsdl");
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(new SoapWsdlResource(resource)) //
                    .register();

            // Metro 从线上 WSDL 创建动态 Dispatch，并发送完整 SOAP 1.1 消息。
            URL wsdl = this.wsdl(context, "EchoService");
            QName serviceName = new QName(ECHO_NAMESPACE, "EchoService");
            QName portName = new QName(ECHO_NAMESPACE, "EchoPort");
            Dispatch<SOAPMessage> dispatch = this.dispatch(wsdl, serviceName, portName, //
                    "urn:meteor:test:echo");
            SOAPMessage request = this.message(ECHO_TYPES_NAMESPACE, "echo", "message", "hello Metro");
            SOAPMessage response = dispatch.invoke(request);

            // 标准客户端收到 WSDL 声明的响应包装与业务结果。
            NodeList results = response.getSOAPBody().getElementsByTagNameNS(ECHO_TYPES_NAMESPACE, "result");
            assertEquals(1, results.getLength());
            assertEquals("hello Metro", results.item(0).getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void metroDispatchInvokesOneWayFromPublishedWsdl() throws Exception {
        // 通过公开 Bootstrap API 发布可观察的 WSDL one-way 服务。
        SignalServiceImpl signal = new SignalServiceImpl();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            URL resource = this.resource("/wsdl/signal.wsdl");
            context.publisher() //
                    .rsfService(SignalService.class, signal) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .bindResource(new SoapWsdlResource(resource)) //
                    .register();

            // Metro 根据在线 WSDL 识别 one-way operation，并使用标准 invokeOneWay 调用。
            URL wsdl = this.wsdl(context, "SignalService");
            QName serviceName = new QName(SIGNAL_NAMESPACE, "SignalService");
            QName portName = new QName(SIGNAL_NAMESPACE, "SignalPort");
            Dispatch<SOAPMessage> dispatch = this.dispatch(wsdl, serviceName, portName, //
                    "urn:meteor:test:signal");
            SOAPMessage request = this.message(SIGNAL_TYPES_NAMESPACE, "signal", "message", "notice Metro");
            dispatch.invokeOneWay(request);

            // HTTP 确认返回后，服务端仍能收到并执行该通知。
            assertTrue("SOAP one-way service was not invoked", signal.awaitInvocation(5, TimeUnit.SECONDS));
            assertEquals("notice Metro", signal.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void meteorClientInvokesMetroProvider() throws Exception {
        // 使用 JDK HTTP Server 承载标准 Metro Provider，服务端不依赖 Meteor 实现。
        Endpoint endpoint = Endpoint.create(SOAPBinding.SOAP11HTTP_BINDING, new MetroEchoProvider());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            HttpContext httpContext = server.createContext("/services/EchoService");
            endpoint.publish(httpContext);
            server.start();
            // Meteor 仅配置出站 SOAP 通道，并通过已有服务发布 API 订阅 WSDL 契约。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                URL resource = this.resource("/wsdl/echo.wsdl");
                MetBindInfo<EchoService> subscribed = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .bindResource(new SoapWsdlResource(resource)) //
                        .register();

                // 公开 MetClient 代理向标准 SOAP Provider 发起真实 HTTP 调用。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient caller = context.getRsfClient(address);
                EchoService remote = caller.getRemote(subscribed);
                assertEquals("hello Meteor", remote.echo("hello Meteor"));
            }
        } finally {
            endpoint.stop();
            server.stop(0);
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private URL resource(String path) {
        URL resource = this.getClass().getResource(path);
        assertNotNull("Missing test resource " + path, resource);
        return resource;
    }

    private URL wsdl(MetContext context, String serviceName) throws Exception {
        int port = context.bindAddress("soap").getPort();
        String path = "/services/" + serviceName + "?wsdl";
        return URI.create("http://127.0.0.1:" + port + path).toURL();
    }

    private Dispatch<SOAPMessage> dispatch(URL wsdl, QName serviceName, QName portName, String soapAction) {
        Service service = Service.create(wsdl, serviceName);
        Dispatch<SOAPMessage> dispatch = service.createDispatch(portName, SOAPMessage.class, Service.Mode.MESSAGE);
        Map<String, Object> requestContext = dispatch.getRequestContext();
        requestContext.put(BindingProvider.SOAPACTION_USE_PROPERTY, Boolean.TRUE);
        requestContext.put(BindingProvider.SOAPACTION_URI_PROPERTY, soapAction);
        return dispatch;
    }

    private SOAPMessage message(String namespace, String operation, String parameter, String value) throws Exception {
        MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
        SOAPMessage message = factory.createMessage();
        SOAPBodyElement body = message.getSOAPBody().addBodyElement(new QName(namespace, operation, "types"));
        SOAPElement argument = body.addChildElement(new QName(namespace, parameter, "types"));
        argument.addTextNode(value);
        message.saveChanges();
        return message;
    }
}
