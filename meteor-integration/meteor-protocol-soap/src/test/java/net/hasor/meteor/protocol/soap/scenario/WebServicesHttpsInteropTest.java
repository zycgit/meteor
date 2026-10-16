/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.xml.namespace.QName;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.xml.ws.developer.JAXWSProperties;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.scenario.example.EchoServiceImpl;
import net.hasor.meteor.protocol.soap.scenario.example.MetroSoap12Provider;
import net.hasor.meteor.protocol.soap.scenario.support.SoapTlsSupport;
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
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", "1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            URL wsdl = this.resource("/wsdl/echo.wsdl");
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(new SoapWsdlResource(wsdl)) //
                    .register();

            // Metro 从相同契约创建 Dispatch，再把调用地址切换到真实 mTLS 端点。
            QName serviceName = new QName(ECHO_NAMESPACE, "EchoService");
            QName portName = new QName(ECHO_NAMESPACE, "EchoPort");
            Service service = Service.create(wsdl, serviceName);
            Dispatch<SOAPMessage> dispatch = service.createDispatch(portName, SOAPMessage.class, Service.Mode.MESSAGE);
            int port = context.bindAddress("soap").getPort();
            String endpoint = "https://localhost:" + port + "/services/EchoService";
            this.secure(dispatch, endpoint, "urn:meteor:test:echo");
            SOAPMessage request = this.message(SOAPConstants.SOAP_1_1_PROTOCOL, ECHO_TYPE_NAMESPACE, //
                    "echo", "message", "hello Metro TLS");
            SOAPMessage response = dispatch.invoke(request);

            // 独立 Metro 客户端读取标准 document/literal 响应。
            NodeList results = response.getSOAPBody().getElementsByTagNameNS(ECHO_TYPE_NAMESPACE, "result");
            assertEquals(1, results.getLength());
            assertEquals("hello Metro TLS", results.item(0).getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void meteorClientCallsMetroSoap12OverMutualHttps() throws Exception {
        // 独立 Metro SOAP 1.2 Provider 由 JDK HttpsServer 承载并要求客户端证书。
        SSLContext sslContext = SoapTlsSupport.sslContext("server", "trust");
        HttpsServer server = this.httpsServer(sslContext, true);
        Endpoint endpoint = Endpoint.create(SOAPBinding.SOAP12HTTP_BINDING, new MetroSoap12Provider());
        try {
            endpoint.publish(server.createContext("/services/EchoService"));
            server.start();

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
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("https", "localhost", port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscription);
                assertEquals("hello Meteor TLS", remote.echo("hello Meteor TLS"));
            }
        } finally {
            endpoint.stop();
            server.stop(0);
        }
    }

    private void secure(Dispatch<SOAPMessage> dispatch, String endpoint, String soapAction) throws Exception {
        SSLContext sslContext = SoapTlsSupport.sslContext("client", "trust");
        Map<String, Object> options = dispatch.getRequestContext();
        options.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint);
        options.put(BindingProvider.SOAPACTION_USE_PROPERTY, true);
        options.put(BindingProvider.SOAPACTION_URI_PROPERTY, soapAction);
        options.put(JAXWSProperties.SSL_SOCKET_FACTORY, sslContext.getSocketFactory());
        options.put(JAXWSProperties.CONNECT_TIMEOUT, 5000);
        options.put(JAXWSProperties.REQUEST_TIMEOUT, 5000);
    }

    private HttpsServer httpsServer(SSLContext sslContext, boolean requireClientCertificate) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                // 使用测试 CA 的默认 TLS 参数，并按场景要求客户端身份。
                SSLParameters ssl = this.getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(requireClientCertificate);
                parameters.setSSLParameters(ssl);
            }
        });
        return server;
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
