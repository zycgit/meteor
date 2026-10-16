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
import javax.xml.namespace.QName;
import com.sun.net.httpserver.HttpServer;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.example.protocol.soap.MetroSoap12Provider;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.NodeList;
import static org.junit.Assert.assertEquals;

public class WebServicesSoap12InteropTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:EchoService";

    @Test(timeout = 20000)
    public void metroClientUsesTheGeneratedSoap12Binding() throws Exception {
        // 用户选择 SOAP 1.2，服务仍按普通 Java 接口发布。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").option("soapVersion", "1.2");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();

            // Metro 从线上 WSDL 选择 SOAP 1.2 port，并按契约发送标准消息。
            int port = context.bindAddress("soap").getPort();
            URL wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl").toURL();
            Service service = Service.create(wsdl, new QName(NAMESPACE, "EchoService"));
            QName portName = new QName(NAMESPACE, "EchoServicePort");
            Dispatch<SOAPMessage> dispatch = service.createDispatch(portName, SOAPMessage.class, Service.Mode.MESSAGE);
            Map<String, Object> options = dispatch.getRequestContext();
            options.put(BindingProvider.SOAPACTION_USE_PROPERTY, true);
            options.put(BindingProvider.SOAPACTION_URI_PROPERTY, NAMESPACE + "/echo");
            options.put("com.sun.xml.ws.connect.timeout", 5000);
            options.put("com.sun.xml.ws.request.timeout", 5000);
            SOAPMessage request = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL).createMessage();
            SOAPBodyElement wrapper = request.getSOAPBody().addBodyElement(new QName(NAMESPACE, "echo", "service"));
            wrapper.addChildElement(new QName("arg0")).addTextNode("hello SOAP 1.2");
            request.saveChanges();
            SOAPMessage response = dispatch.invoke(request);

            // 标准客户端读取版本正确的 Envelope 和服务返回值。
            assertEquals(SOAPBinding.SOAP12HTTP_BINDING, dispatch.getBinding().getBindingID());
            String envelopeNamespace = response.getSOAPPart().getEnvelope().getNamespaceURI();
            assertEquals(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, envelopeNamespace);
            NodeList values = response.getSOAPBody().getElementsByTagNameNS("", "return");
            assertEquals(1, values.getLength());
            assertEquals("hello SOAP 1.2", values.item(0).getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void meteorClientCallsAnIndependentMetroSoap12Provider() throws Exception {
        // 对端使用 Metro 自己的 SOAP 1.2 binding，不经过 Meteor 协议实现。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Endpoint endpoint = Endpoint.create(SOAPBinding.SOAP12HTTP_BINDING, new MetroSoap12Provider());
        try {
            endpoint.publish(server.createContext("/services/EchoService"));
            server.start();

            // Meteor 只启动出站通道，订阅 Java 接口后通过公开客户端调用。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http").protocol("soap") //
                    .contextPath("/services").option("soapVersion", "1.2");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> subscription = context.publisher().rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService").protocol("soap").register();
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscription);
                assertEquals("hello Metro 1.2", remote.echo("hello Metro 1.2"));
            }
        } finally {
            endpoint.stop();
            server.stop(0);
        }
    }
}
