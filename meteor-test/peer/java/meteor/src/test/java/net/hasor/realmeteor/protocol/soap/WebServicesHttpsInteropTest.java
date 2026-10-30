/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URL;
import java.util.Map;
import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.protocol.soap.support.SoapTlsSupport;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.w3c.dom.NodeList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WebServicesHttpsInteropTest {
    private static final String ECHO_NAMESPACE      = "urn:meteor:test:echo";
    private static final String ECHO_TYPE_NAMESPACE = "urn:meteor:test:echo:types";

    @Test(timeout = 20000)
    public void metroClientCallsMeteorOverMutualHttps() throws Exception {
        // Meteor 通过公开 Bootstrap 在双向 TLS HTTP/1.1 上发布外部 WSDL。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        SoapTlsSupport.server(configuration, "http/1.1", true) //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", "1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            URL wsdl = this.resource("/protocol/wsdl/echo.wsdl");
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(wsdl)) //
                    .register();

            // Metro 从相同契约创建 Dispatch，再把调用地址切换到真实 mTLS 端点。
            QName serviceName = new QName(ECHO_NAMESPACE, "EchoService");
            QName portName = new QName(ECHO_NAMESPACE, "EchoPort");
            Map<String, Object> dispatch = SoapPeerProcess.dispatch(wsdl, serviceName, portName, null);
            int port = context.getListenAddress("soap").getPort();
            String endpoint = "https://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService";
            this.secure(dispatch, endpoint, "urn:meteor:test:echo");
            SOAPMessage request = this.message(SOAPConstants.SOAP_1_1_PROTOCOL, ECHO_TYPE_NAMESPACE, //
                    "echo", "message", "hello Metro TLS");
            Map<String, Object> reply = SoapPeerProcess.invokeMetro(dispatch, request);
            SOAPMessage response = SoapPeerProcess.response(reply);

            // 独立 Metro 客户端读取标准 document/literal 响应。
            NodeList results = response.getSOAPBody().getElementsByTagNameNS(ECHO_TYPE_NAMESPACE, "result");
            assertEquals(1, results.getLength());
            assertEquals("hello Metro TLS", results.item(0).getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void meteorClientCallsMetroSoap12OverMutualHttps() throws Exception {
        // 独立 Metro SOAP 1.2 Provider 由 JDK HttpsServer 承载并要求客户端证书。
        try (SoapPeerProcess server = SoapPeerProcess.metroServer("echo", "1.2", true)) {
            // Meteor 只配置正式出站 TLS 参数，通过公开服务订阅入口创建代理。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            SoapTlsSupport.client(configuration, 1, "http/1.1", true) //
                    .protocol("soap").addressScheme("https") //
                    .contextPath("/services") //
                    .option("soapVersion", "1.2");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> subscription = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // Meteor 代理向独立 Metro HTTPS 端点发起 SOAP 1.2 调用。
                int port = server.getPort();
                InterAddress address = new InterAddress("https", server.host(), port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscription);
                assertEquals("hello Meteor TLS", remote.echo("hello Meteor TLS"));
            }
        }
    }

    private void secure(Map<String, Object> options, String endpoint, String soapAction) {
        // 原生进程从显式证书目录装载客户端身份，不借用宿主 SSLContext。
        options.put("endpoint", endpoint);
        options.put("soapAction", soapAction);
        options.put("tlsDirectory", SoapPeerProcess.tlsDirectory());
    }

    private SOAPMessage message(String protocol, String namespace, String operation, //
            String parameter, String value) throws Exception {
        MessageFactory factory = MessageFactory.newInstance(protocol);
        SOAPMessage message = factory.createMessage();
        SOAPBodyElement body = message.getSOAPBody().addBodyElement(new QName(namespace, operation, "types"));
        body.addChildElement(new QName(namespace, parameter, "types")).addTextNode(value);
        message.saveChanges();
        return message;
    }

    private URL resource(String path) {
        URL resource = this.getClass().getResource(path);
        assertNotNull("Missing test resource " + path, resource);
        return resource;
    }
}
