/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "soap", version = "1.2")
public class WebServicesSoap12InteropTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:EchoService";

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void metroClientUsesTheGeneratedSoap12Binding() throws Exception {
        // 用户选择 SOAP 1.2，服务仍按普通 Java 接口发布。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").contextPath("/services").option("soapVersion", "1.2");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();

            // Metro 从线上 WSDL 选择 SOAP 1.2 port，并按契约发送标准消息。
            int port = context.getListenAddress("soap").getPort();
            String endpoint = "http://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService?wsdl";
            URL wsdl = URI.create(endpoint).toURL();
            QName serviceName = new QName(NAMESPACE, "EchoService");
            QName portName = new QName(NAMESPACE, "EchoServicePort");
            Map<String, Object> dispatch = SoapPeerProcess.dispatch(wsdl, serviceName, portName, NAMESPACE + "/echo");
            SOAPMessage request = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL).createMessage();
            SOAPBodyElement wrapper = request.getSOAPBody().addBodyElement(new QName(NAMESPACE, "echo", "service"));
            wrapper.addChildElement(new QName("arg0")).addTextNode("hello SOAP 1.2");
            request.saveChanges();
            Map<String, Object> reply = SoapPeerProcess.invokeMetro(dispatch, request);
            SOAPMessage response = SoapPeerProcess.response(reply);

            // 标准客户端读取版本正确的 Envelope 和服务返回值。
            assertEquals(SOAPBinding.SOAP12HTTP_BINDING, reply.get("bindingId"));
            String envelopeNamespace = response.getSOAPPart().getEnvelope().getNamespaceURI();
            assertEquals(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, envelopeNamespace);
            NodeList values = response.getSOAPBody().getElementsByTagNameNS("", "return");
            assertEquals(1, values.getLength());
            assertEquals("hello SOAP 1.2", values.item(0).getTextContent());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsAnIndependentMetroSoap12Provider() throws Exception {
        // 对端使用 Metro 自己的 SOAP 1.2 binding，不经过 Meteor 协议实现。
        try (SoapPeerProcess server = SoapPeerProcess.metroServer("echo", "1.2", false)) {
            // Meteor 只启动出站通道，订阅 Java 接口后通过公开客户端调用。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http").protocol("soap") //
                    .contextPath("/services").option("soapVersion", "1.2");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> subscription = context.publisher().rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService").protocol("soap").register();
                int port = server.getPort();
                InterAddress address = new InterAddress("soap", server.host(), port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscription);
                assertEquals("hello Metro 1.2", remote.echo("hello Metro 1.2"));
            }
        }
    }
}
