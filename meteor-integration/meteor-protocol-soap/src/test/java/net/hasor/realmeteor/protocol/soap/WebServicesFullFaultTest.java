/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.SoapFault;
import org.junit.Test;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class WebServicesFullFaultTest {
    private static final String SOAP_12 = "http://www.w3.org/2003/05/soap-envelope";

    @Test(timeout = 20000)
    public void soap12FaultPreservesAllStandardFieldsAcrossHttp() throws Exception {
        // 服务声明完整 SOAP 1.2 Fault，Detail 的 QName 引用继承自祖先命名空间。
        Element holder = this.element("""
                <holder xmlns:m="urn:example:fault" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <Detail><m:Problem xsi:type="m:ProblemType"><m:id>42</m:id></m:Problem></Detail>
                </holder>
                """);
        Element detail = (Element) holder.getElementsByTagName("Detail").item(0);
        SoapFault declared = SoapFault.create(new QName(SOAP_12, "Sender"), "invalid request") //
                .addSubcode(new QName("urn:example:fault", "Validation")) //
                .addSubcode(new QName("urn:example:fault", "MissingField")) //
                .addReason("zh-CN", "缺少必要参数") //
                .node("urn:example:node") //
                .role("urn:example:role") //
                .detail(detail);
        MetConfiguration incoming = this.incoming("1.2");
        MetConfiguration outgoing = this.outgoing("1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> {
                        throw declared;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 独立上下文通过真实 HTTP 调用，原始 SOAP Fault 类型到达调用方。
            MetFuture invocation = client.getRsfClient(server.getListenAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> invocation.getData(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SoapFault);
            SoapFault fault = (SoapFault) failure.getCause();

            // QName、子码链、多语言解释和任意 XML Detail 均保留语义。
            assertEquals(new QName(SOAP_12, "Sender"), fault.getCode());
            assertEquals(2, fault.getSubcodes().size());
            assertEquals(new QName("urn:example:fault", "MissingField"), fault.getSubcodes().get(1));
            assertEquals(2, fault.getReasons().size());
            assertEquals("zh-CN", fault.getReasons().get(1).language());
            assertEquals("缺少必要参数", fault.getReasons().get(1).text());
            assertEquals("urn:example:node", fault.getNode());
            assertEquals("urn:example:role", fault.getRole());
            Element receivedDetail = fault.getDetail();
            Element problem = (Element) receivedDetail.getElementsByTagNameNS("urn:example:fault", "Problem").item(0);
            assertEquals("urn:example:fault", problem.lookupNamespaceURI("m"));
            assertEquals("42", problem.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void soap11FaultPreservesActorDetailAndQualifiedExtensions() throws Exception {
        // SOAP 1.1 应用 Fault 支持 faultactor 和额外命名空间限定子元素。
        SoapFault declared = SoapFault.create(new QName("urn:example:fault", "Client.Validation"), "invalid request") //
                .actor("urn:example:actor") //
                .detail(this.element("<detail><p:Problem xmlns:p='urn:example:fault'>missing</p:Problem></detail>")) //
                .addExtension(this.element("<p:Retry xmlns:p='urn:example:fault' after='30'/>"));
        MetConfiguration incoming = this.incoming("1.1");
        MetConfiguration outgoing = this.outgoing("1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> {
                        throw declared;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 调用方收到具体 Fault，不需要从错误消息反向解析 XML。
            MetFuture invocation = client.getRsfClient(server.getListenAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> invocation.getData(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SoapFault);
            SoapFault fault = (SoapFault) failure.getCause();

            assertEquals(new QName("urn:example:fault", "Client.Validation"), fault.getCode());
            assertEquals("urn:example:actor", fault.getActor());
            assertEquals("missing", fault.getDetail().getTextContent());
            assertEquals(1, fault.getExtensions().size());
            assertEquals("Retry", fault.getExtensions().get(0).getLocalName());
            assertEquals("30", fault.getExtensions().get(0).getAttribute("after"));
        }
    }

    @Test(timeout = 20000)
    public void duplicateReasonLanguageRemainsReadable() throws Exception {
        // SOAP 1.2 对不同语言仅作 SHOULD 建议，同语言的多段说明仍是合法 Fault。
        SoapFault declared = SoapFault.create(new QName(SOAP_12, "Receiver"), "first explanation") //
                .addReason("en", "second explanation");
        MetConfiguration incoming = this.incoming("1.2");
        MetConfiguration outgoing = this.outgoing("1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> {
                        throw declared;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 两条说明经 HTTP 保留顺序，未被错误当作重复字段拒绝。
            MetFuture invocation = client.getRsfClient(server.getListenAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> invocation.getData(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SoapFault);
            SoapFault fault = (SoapFault) failure.getCause();
            assertEquals(2, fault.getReasons().size());
            assertEquals("second explanation", fault.getReasons().get(1).text());
        }
    }

    private MetConfiguration incoming(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .option("soapVersion", version) //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoing(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .option("soapVersion", version) //
                .contextPath("/services");
        return configuration;
    }

    private Element element(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}
