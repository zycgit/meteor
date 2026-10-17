/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapWsdlHttpResourceTest {
    private final Map<String, byte[]> documents = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private final List<String>        requests  = new CopyOnWriteArrayList<>();
    private       HttpServer          server;

    @Before
    public void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::serve);
        this.server.start();
    }

    @After
    public void stopServer() {
        this.server.stop(0);
    }

    @Test
    public void queryOnlySchemaImportPreservesEndpointPath() throws Exception {
        // WSDL 与 XSD 由同一路径上的不同查询参数提供。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        this.document("/services/Echo?xsd=types.xsd", this.fixture("echo-types.xsd"));

        // query-only 导入保留服务路径，不能退回父目录。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertNotNull(resource.definition().operation("echo"));
        assertTrue(resource.resourceNames().contains("Echo?wsdl"));
        assertTrue(resource.resourceNames().contains("Echo?xsd=types.xsd"));
        assertEquals(List.of("/services/Echo?wsdl", "/services/Echo?xsd=types.xsd"), this.requests);
    }

    @Test
    public void absoluteSameOriginSchemaImportIsLoaded() throws Exception {
        // 外部契约允许显式写出同源端点及其查询参数。
        String location = this.url("/services/Echo?xsd=types.xsd").toExternalForm();
        this.document("/services/Echo?wsdl", this.echoWsdl(location));
        this.document("/services/Echo?xsd=types.xsd", this.fixture("echo-types.xsd"));

        // 完整 URL 与相对引用得到相同的资源名。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertNotNull(resource.definition().operation("echo"));
        assertTrue(resource.resourceNames().contains("Echo?xsd=types.xsd"));
    }

    @Test
    public void rootRelativeSameOriginSchemaImportIsLoaded() throws Exception {
        // 根相对路径仍位于入口 WSDL 的同源资源目录。
        this.document("/services/Echo?wsdl", this.echoWsdl("/services/Echo?xsd=types.xsd"));
        this.document("/services/Echo?xsd=types.xsd", this.fixture("echo-types.xsd"));

        // 无需把根相对引用伪装成本地文件路径。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertNotNull(resource.definition().operation("echo"));
        assertTrue(resource.resourceNames().contains("Echo?xsd=types.xsd"));
    }

    @Test
    public void distinctQueriesOnOnePathRetainTheirSchemaDocuments() throws Exception {
        // 第一份 XSD 继续按 query-only 地址 include 第二份同命名空间 Schema。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        String schema = this.addIncludes(this.fixture("echo-types.xsd"), List.of("?xsd=extra.xsd"));
        this.document("/services/Echo?xsd=types.xsd", schema);
        this.document("/services/Echo?xsd=extra.xsd", this.emptySchema());

        // 资源图以路径和原始 query 区分文档，不能把两份 XSD 合并。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertEquals(3, resource.resourceNames().size());
        assertTrue(resource.resourceNames().contains("Echo?xsd=types.xsd"));
        assertTrue(resource.resourceNames().contains("Echo?xsd=extra.xsd"));
        assertNotNull(resource.definition().operation("echo"));
    }

    @Test
    public void queryOnlyWsdlImportLoadsItsQueryOnlySchema() throws Exception {
        // 入口只发布 service port，binding 和 message 来自另一份 query WSDL。
        this.document("/services/Echo?wsdl", this.importingWsdl());
        this.document("/services/Echo?wsdl=contract", this.echoWsdl("?xsd=types.xsd"));
        this.document("/services/Echo?xsd=types.xsd", this.fixture("echo-types.xsd"));

        // WSDL 与其后续 XSD 导入均保留当前端点路径。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertNotNull(resource.definition().operation("echo"));
        assertTrue(resource.resourceNames().contains("Echo?wsdl=contract"));
        assertTrue(resource.resourceNames().contains("Echo?xsd=types.xsd"));
        assertEquals(3, this.requests.size());
    }

    @Test
    public void renderedQueryResourceGraphCanBeReloadedAtAnotherEndpoint() throws Exception {
        // 原始在线契约包含两级 query XSD，先完成导入及契约解析。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        String schema = this.addIncludes(this.fixture("echo-types.xsd"), List.of("?xsd=extra.xsd"));
        this.document("/services/Echo?xsd=types.xsd", schema);
        this.document("/services/Echo?xsd=extra.xsd", this.emptySchema());
        SoapWsdlResource original = new SoapWsdlResource(this.url("/services/Echo?wsdl"));

        // 将 WSDL 和每份 XSD 按公开渲染结果发布到新端点。
        URI endpoint = this.url("/published/Echo").toURI();
        byte[] renderedWsdl = original.renderWsdl(endpoint);
        this.documents.put("/published/Echo?wsdl", renderedWsdl);
        for (String resourceName : List.of("Echo?xsd=types.xsd", "Echo?xsd=extra.xsd")) {
            String encoded = URLEncoder.encode(resourceName, StandardCharsets.UTF_8);
            byte[] renderedSchema = original.renderResource(resourceName, endpoint);
            this.documents.put("/published/Echo?xsd=" + encoded, renderedSchema);
        }

        // 从新地址完整重载，确保渲染的 include 不再引用旧地址。
        SoapWsdlResource relocated = new SoapWsdlResource(this.url("/published/Echo?wsdl"));
        assertNotNull(relocated.definition().operation("echo"));
        assertEquals(3, relocated.resourceNames().size());
        String wsdl = new String(renderedWsdl, StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("location=\"" + endpoint + "\""));
        String extraRequest = "/published/Echo?xsd=Echo%3Fxsd%3Dextra.xsd";
        assertTrue(this.requests.contains(extraRequest));
    }

    @Test
    public void renderedEndpointPreservesItsEscapedPath() throws Exception {
        // 在线契约可以重新发布到包含转义字符的上下文路径。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        this.document("/services/Echo?xsd=types.xsd", this.fixture("echo-types.xsd"));
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));

        // address 与依赖链接保留原始转义，同时清理传入端点的查询与片段。
        URI endpoint = this.url("/soap%20services/Echo?existing=1#view").toURI();
        String rendered = new String(resource.renderWsdl(endpoint), StandardCharsets.UTF_8);
        String address = this.url("/soap%20services/Echo").toExternalForm();
        assertTrue(rendered.contains("location=\"" + address + "\""));
        assertTrue(rendered.contains("schemaLocation=\"" + address + "?xsd="));
        assertFalse(rendered.contains("%2520"));
    }

    @Test
    public void schemaImportFromAnotherPortIsRejectedBeforeFetching() throws Exception {
        // 主机相同但端口不同仍然属于跨源地址。
        int port = this.server.getAddress().getPort();
        int otherPort = port == 65535 ? port - 1 : port + 1;
        String location = "http://127.0.0.1:" + otherPort + "/services/Echo?xsd=types.xsd";
        this.document("/services/Echo?wsdl", this.echoWsdl(location));

        // 在发起依赖请求之前拒绝跨源导入。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("origin"));
        assertEquals(List.of("/services/Echo?wsdl"), this.requests);
    }

    @Test
    public void sameOriginSchemaOutsideTheResourceDirectoryIsRejected() throws Exception {
        // 同源地址不能访问入口资源目录之外的其他应用路径。
        this.document("/services/Echo?wsdl", this.echoWsdl("/private/types.xsd"));
        this.document("/private/types.xsd", this.fixture("echo-types.xsd"));

        // 资源目录限制在网络读取之前生效。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("resource root"));
        assertFalse(this.requests.contains("/private/types.xsd"));
    }

    @Test
    public void percentEncodedParentPathIsRejected() throws Exception {
        // 百分号编码不能掩盖向上跳转的路径段。
        this.document("/services/Echo?wsdl", this.echoWsdl("/services/%2e%2e/private/types.xsd"));

        // 对解码后的路径执行目录约束，不请求依赖资源。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("resource root"));
        assertEquals(List.of("/services/Echo?wsdl"), this.requests);
    }

    @Test
    public void percentEncodedBackslashPathIsRejected() throws Exception {
        // 反斜线经过 URL 编码后也不属于可接受的资源路径。
        this.document("/services/Echo?wsdl", this.echoWsdl("/services/folder%5ctypes.xsd"));

        // 防止后续服务器把反斜线作为目录分隔符解释。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("forward slashes"));
        assertEquals(List.of("/services/Echo?wsdl"), this.requests);
    }

    @Test
    public void redirectingSchemaImportIsRejectedWithoutFollowing() throws Exception {
        // Schema 地址返回同源 302，也不能隐式改变已经校验过的资源位置。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        this.redirects.put("/services/Echo?xsd=types.xsd", "/services/actual.xsd");
        this.document("/services/actual.xsd", this.fixture("echo-types.xsd"));

        // Loader 显式拒绝重定向，不跟随 Location 发起下一次请求。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("redirects"));
        assertFalse(this.requests.contains("/services/actual.xsd"));
    }

    @Test
    public void fragmentInSchemaImportIsRejected() throws Exception {
        // fragment 不能用来标识另一份 WSDL/XSD 资源。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd#schema"));

        // 无法定位完整文档的引用在网络读取之前报错。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("Invalid same-origin"));
        assertEquals(List.of("/services/Echo?wsdl"), this.requests);
    }

    @Test
    public void sixtyFourDistinctQueryResourcesAreAccepted() throws Exception {
        // 一份 WSDL、一份主 XSD 与六十二份 include 正好达到资源数量上限。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        List<String> includes = this.querySchemas(62);
        String schema = this.addIncludes(this.fixture("echo-types.xsd"), includes);
        this.document("/services/Echo?xsd=types.xsd", schema);

        // 相同路径下的不同 query 都计入资源图，合法上限仍可解析。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertEquals(64, resource.resourceNames().size());
        assertNotNull(resource.definition().operation("echo"));
    }

    @Test
    public void sixtyFifthDistinctQueryResourceIsRejected() throws Exception {
        // 所有依赖都位于同一路径，新增 query 仍然是一份独立资源。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        List<String> includes = this.querySchemas(63);
        String schema = this.addIncludes(this.fixture("echo-types.xsd"), includes);
        this.document("/services/Echo?xsd=types.xsd", schema);

        // 数量限制不允许被只改变 query 的资源图绕过。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("resource count exceeds 64"));
    }

    @Test
    public void eighthQueryImportDepthIsAccepted() throws Exception {
        // WSDL 为深度零，主 XSD 为深度一，后续 include 链延伸到八。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        this.querySchemaChain(8);

        // 合法深度边界可以完成 Schema 编译与服务解析。
        SoapWsdlResource resource = new SoapWsdlResource(this.url("/services/Echo?wsdl"));
        assertEquals(9, resource.resourceNames().size());
        assertNotNull(resource.definition().operation("echo"));
    }

    @Test
    public void ninthQueryImportDepthIsRejected() throws Exception {
        // 每层 include 都只改变 query，资源路径始终相同。
        this.document("/services/Echo?wsdl", this.echoWsdl("?xsd=types.xsd"));
        this.querySchemaChain(9);

        // 深度控制针对资源依赖链，而不是不同的路径数量。
        URL wsdl = this.url("/services/Echo?wsdl");
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("import depth exceeds 8"));
    }

    private URL url(String path) throws IOException {
        int port = this.server.getAddress().getPort();
        return URI.create("http://127.0.0.1:" + port + path).toURL();
    }

    private void document(String path, String body) {
        this.documents.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private String fixture(String name) throws IOException {
        try (InputStream input = this.getClass().getResourceAsStream("/wsdl/" + name)) {
            assertNotNull("Missing WSDL fixture: " + name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String echoWsdl(String schemaLocation) throws IOException {
        String wsdl = this.fixture("echo.wsdl");
        return wsdl.replace("schemaLocation=\"echo-types.xsd\"", "schemaLocation=\"" + schemaLocation + "\"");
    }

    private String importingWsdl() {
        return """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                    xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:tns="urn:meteor:test:echo" targetNamespace="urn:meteor:test:echo">
                    <wsdl:import namespace="urn:meteor:test:echo" location="?wsdl=contract"/>
                    <wsdl:service name="EchoService">
                        <wsdl:port name="EchoPort" binding="tns:EchoBinding">
                            <soap:address location="http://127.0.0.1:1/placeholder/EchoService"/>
                        </wsdl:port>
                    </wsdl:service>
                </wsdl:definitions>
                """;
    }

    private String emptySchema() {
        return """
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                    targetNamespace="urn:meteor:test:echo:types" elementFormDefault="qualified">
                </xsd:schema>
                """;
    }

    private String addIncludes(String schema, List<String> locations) {
        StringBuilder includes = new StringBuilder();
        for (String location : locations) {
            includes.append("<xsd:include schemaLocation=\"").append(location).append("\"/>");
        }
        int schemaStart = schema.indexOf("<xsd:schema");
        int contentStart = schema.indexOf('>', schemaStart) + 1;
        return schema.substring(0, contentStart) + includes + schema.substring(contentStart);
    }

    private List<String> querySchemas(int count) {
        List<String> locations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String query = "?xsd=part-" + index + ".xsd";
            locations.add(query);
            this.document("/services/Echo" + query, this.emptySchema());
        }
        return locations;
    }

    private void querySchemaChain(int lastDepth) throws IOException {
        // 每层单独发布，使失败原因只取决于依赖深度。
        String first = this.addIncludes(this.fixture("echo-types.xsd"), List.of("?xsd=depth-2.xsd"));
        this.document("/services/Echo?xsd=types.xsd", first);
        for (int depth = 2; depth <= lastDepth; depth++) {
            String schema = this.emptySchema();
            if (depth < lastDepth) {
                String next = "?xsd=depth-" + (depth + 1) + ".xsd";
                schema = this.addIncludes(schema, List.of(next));
            }
            this.document("/services/Echo?xsd=depth-" + depth + ".xsd", schema);
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toASCIIString();
        this.requests.add(path);
        try {
            // 重定向由场景显式配置，成功文档从完整 path/query 取出。
            String redirect = this.redirects.get(path);
            if (redirect != null) {
                exchange.getResponseHeaders().set("Location", redirect);
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            byte[] body = this.documents.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            // 每次响应均准确发送正文长度，并在完成后释放 HTTP 交换。
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
