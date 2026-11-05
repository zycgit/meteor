/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.example.protocol.soap.DeclaredEcho;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebServicesBootstrapTest {
    @Test
    public void defaultJavaTypeNamePublishesCallableSoapAndWsdl() throws Exception {
        // 服务端监听 SOAP，客户端只准备出站 SOAP 通道。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端只发布和订阅 Java 接口，由 SOAP 扩展自动生成相同契约。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .protocol("soap") //
                    .register();

            // 通过公开 MetClient 代理走真实 HTTP，验证 SOAP request-response 往返。
            MetClient caller = client.getRsfClient(server.getListenAddress("soap"));
            EchoService remote = caller.getRemote(subscribed);
            assertEquals("hello SOAP", remote.echo("hello SOAP"));

            // WSDL 路由和客户端使用相同默认名，不向服务别名表写入推导结果。
            String name = EchoService.class.getCanonicalName();
            URI endpoint = URI.create("http://" + server.getListenAddress("soap").getHostPort() + "/services/" + name);
            Document wsdl = this.wsdl(URI.create(endpoint + "?wsdl"));
            Element service = this.wsdlElement(wsdl, "service");
            Element address = (Element) wsdl.getElementsByTagNameNS( //
                    "http://schemas.xmlsoap.org/wsdl/soap/", "address").item(0);
            assertEquals(name, service.getAttribute("name"));
            assertEquals("urn:hasor:meteor:soap:" + name, wsdl.getDocumentElement().getAttribute("targetNamespace"));
            assertEquals(endpoint.toString(), address.getAttribute("location"));
            assertNull(subscribed.getAliasName("soap"));
        }
    }

    @Test
    public void declaredServiceNamePublishesCallableSoapAndWsdl() throws Exception {
        // 公共注解指定服务名，两端都不需要重复声明协议别名。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DeclaredEcho.class, value -> "declared:" + value).protocol("soap").register();
            MetBindInfo<DeclaredEcho> subscription = client.publisher().rsfService(DeclaredEcho.class) //
                    .protocol("soap").register();

            // 真实 RPC 和 WSDL 都落在公共声明名对应的地址。
            DeclaredEcho remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            assertEquals("declared:hello", remote.echo("hello"));
            String base = "http://" + server.getListenAddress("soap").getHostPort();
            URI endpoint = URI.create(base + "/services/PublicEcho");
            Document wsdl = this.wsdl(URI.create(endpoint + "?wsdl"));
            Element service = this.wsdlElement(wsdl, "service");
            assertEquals("PublicEcho", service.getAttribute("name"));
        }
    }

    @Test
    public void explicitAliasOverridesDefaultRoutingAndGeneratedWsdl() throws Exception {
        // 协议别名覆盖内部服务登记名，线上地址和生成契约同时采用它。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EchoService.class, new EchoServiceImpl()).name("InternalEcho") //
                    .aliasName("soap", "ExternalEcho").protocol("soap").register();
            MetBindInfo<EchoService> subscription = client.publisher().rsfService(EchoService.class) //
                    .name("InternalEcho").aliasName("soap", "ExternalEcho").protocol("soap").register();

            // 调用和 WSDL 使用别名，内部登记名不会产生额外 HTTP 端点。
            EchoService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            assertEquals("aliased", remote.echo("aliased"));
            String base = "http://" + server.getListenAddress("soap").getHostPort() + "/services/";
            Document wsdl = this.wsdl(URI.create(base + "ExternalEcho?wsdl"));
            Element service = this.wsdlElement(wsdl, "service");
            assertEquals("ExternalEcho", service.getAttribute("name"));
            HttpRequest internal = HttpRequest.newBuilder(URI.create(base + "InternalEcho?wsdl")).GET().build();
            HttpResponse<Void> unavailable = HttpClient.newHttpClient().send( //
                    internal, HttpResponse.BodyHandlers.discarding());
            assertEquals(404, unavailable.statusCode());
        }
    }

    @Test
    public void defaultEndpointPreservesExternalWsdlContract() throws Exception {
        // 外部契约的 XML 名称由 WSDL 决定，默认服务名只用于 HTTP 地址。
        SoapWsdlResource contract = new SoapWsdlResource(this.getClass().getResource("/wsdl/echo.wsdl"));
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .protocol("soap").bindResource("soap", contract).register();
            MetBindInfo<EchoService> subscription = client.publisher().rsfService(EchoService.class) //
                    .protocol("soap").bindResource("soap", contract).register();

            // 在默认路径完成调用，同时保留外部 namespace、service 和 port 名。
            EchoService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            assertEquals("external", remote.echo("external"));
            String base = "http://" + server.getListenAddress("soap").getHostPort() + "/services/";
            URI endpoint = URI.create(base + EchoService.class.getCanonicalName());
            Document wsdl = this.wsdl(URI.create(endpoint + "?wsdl"));
            Element service = this.wsdlElement(wsdl, "service");
            Element port = this.wsdlElement(wsdl, "port");
            Element address = (Element) wsdl.getElementsByTagNameNS( //
                    "http://schemas.xmlsoap.org/wsdl/soap/", "address").item(0);
            assertEquals("urn:meteor:test:echo", wsdl.getDocumentElement().getAttribute("targetNamespace"));
            assertEquals("EchoService", service.getAttribute("name"));
            assertEquals("EchoPort", port.getAttribute("name"));
            assertEquals(endpoint.toString(), address.getAttribute("location"));
        }
    }

    @Test
    public void explicitAliasCannotShadowAnExistingDefaultName() throws Exception {
        // 默认名与别名进入相同挂载时必须在发布阶段发现冲突。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = configuration.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<EchoService> existing = server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .name("SharedEcho").protocol("soap").register();

            // 第二个内部服务名不同，但其线上别名遮蔽了已发布的默认服务名。
            assertThrows(IllegalArgumentException.class, () -> server.publisher() //
                    .rsfService(EchoService.class, value -> "duplicate:" + value).name("OtherEcho") //
                    .aliasName("soap", "SharedEcho").protocol("soap").register());
            String base = "http://" + server.getListenAddress("soap").getHostPort();
            URI endpoint = URI.create(base + "/services/SharedEcho");
            Document wsdl = this.wsdl(URI.create(endpoint + "?wsdl"));
            assertEquals("SharedEcho", this.wsdlElement(wsdl, "service").getAttribute("name"));
            assertEquals(existing.getBindID(), server.getServiceInfo(existing.getBindID()).getBindID());
            assertEquals(1, server.getServiceIDs().size());

            // 冲突发布没有替换原提供者，实现及线上端点仍可正常调用。
            MetBindInfo<EchoService> subscription = client.publisher().rsfService(EchoService.class) //
                    .name("SharedEcho").protocol("soap").register();
            EchoService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            assertEquals("accepted", remote.echo("accepted"));
        }
    }

    @Test
    public void identicalDefaultNamesRemainIndependentOnDifferentMounts() throws Exception {
        // 同一物理 HTTP 端点上的两个挂载允许独立发布相同有效服务名。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/first").addContextPath("/second");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/first").addContextPath("/second");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EchoService.class, value -> "first:" + value) //
                    .name("SharedEcho").version("1").protocol("soap") //
                    .contextPathByPrefix("soap", "/first").register();
            server.publisher().rsfService(EchoService.class, value -> "second:" + value) //
                    .name("SharedEcho").version("2").protocol("soap") //
                    .contextPathByPrefix("soap", "/second").register();
            MetBindInfo<EchoService> first = client.publisher().rsfService(EchoService.class) //
                    .name("SharedEcho").version("1").protocol("soap") //
                    .contextPathByPrefix("soap", "/first").register();
            MetBindInfo<EchoService> second = client.publisher().rsfService(EchoService.class) //
                    .name("SharedEcho").version("2").protocol("soap") //
                    .contextPathByPrefix("soap", "/second").register();

            // 两次真实调用分别命中自己的路径，不能被同名服务覆盖。
            MetClient caller = client.getRsfClient(server.getListenAddress("soap"));
            assertEquals("first:hello", caller.getRemote(first).echo("hello"));
            assertEquals("second:hello", caller.getRemote(second).echo("hello"));
        }
    }

    private Element wsdlElement(Document wsdl, String name) {
        return (Element) wsdl.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", name).item(0);
    }

    private Document wsdl(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send( //
                request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
    }
}
