/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import static org.junit.Assert.assertEquals;

public class XmlRpcWireScenarioTest {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test(timeout = 15000)
    public void positionalParametersFollowTheJavaDeclaration() throws Exception {
        // 位置参数按照接口声明顺序传入，减法使顺序差异可见。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.subtract</methodName><params>
                    <param><value><i4>7</i4></value></param>
                    <param><value><int>5</int></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("2", this.text(xml, "/methodResponse/params/param/value/*"));
        }
    }

    @Test(timeout = 15000)
    public void untypedValuesAreStrings() throws Exception {
        // 没有类型标签的 value 按标准作为字符串。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value>plain text</value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("plain text", this.text(xml, "/methodResponse/params/param/value/string"));
        }
    }

    @Test(timeout = 15000)
    public void unicodeAndXmlEscapingRemainBusinessText() throws Exception {
        // UTF-8 和 XML 转义还原为原始业务文本。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><string>你好 &amp; &lt;Meteor&gt;</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("你好 & <Meteor>", this.text(xml, "/methodResponse/params/param/value/string"));
        }
    }

    @Test(timeout = 15000)
    public void booleanUsesTheStandardZeroAndOneEncoding() throws Exception {
        // boolean 输入值 1 以布尔值进入 Java 方法。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.negate</methodName><params>
                    <param><value><boolean>1</boolean></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("0", this.text(xml, "/methodResponse/params/param/value/boolean"));
        }
    }

    @Test(timeout = 15000)
    public void floatingPointValuesRetainTheirFraction() throws Exception {
        // double 通过业务计算返回非整数结果。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.half</methodName><params>
                    <param><value><double>7.5</double></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("3.75", this.text(xml, "/methodResponse/params/param/value/double"));
        }
    }

    @Test(timeout = 15000)
    public void base64ValuesReachTheServiceAsBytes() throws Exception {
        // base64 编码承载包含零字节和高位字节的数据。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.binary</methodName><params>
                    <param><value><base64>AAH/f4A=</base64></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("AAH/f4A=", this.text(xml, "/methodResponse/params/param/value/base64"));
        }
    }

    @Test(timeout = 15000)
    public void dateTimeUsesTheStandardWireRepresentation() throws Exception {
        // dateTime.iso8601 作为 Date 参数和返回值往返。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.date</methodName><params>
                    <param><value><dateTime.iso8601>20261016T12:34:56</dateTime.iso8601></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("20261016T12:34:56", this.text(xml, "/methodResponse/params/param/value/dateTime.iso8601"));
        }
    }

    @Test(timeout = 15000)
    public void arrayOfStructValuesBindsGenericBusinessObjects() throws Exception {
        // array 中的 struct 按泛型签名映射为业务 POJO。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.people</methodName><params>
                    <param><value><array><data><value><struct>
                    <member><name>name</name><value><string>Meteor</string></value></member>
                    <member><name>age</name><value><int>11</int></value></member>
                    </struct></value></data></array></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            String member = "/methodResponse/params/param/value/array/data/value/struct/member";
            assertEquals("Meteor", this.text(xml, member + "[name='name']/value/string"));
            assertEquals("11", this.text(xml, member + "[name='age']/value/*"));
        }
    }

    @Test(timeout = 15000)
    public void structMembersRemainNamedBusinessValues() throws Exception {
        // struct 的名字和值共同到达 Map 参数。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.struct</methodName><params>
                    <param><value><struct>
                    <member><name>label</name><value><string>sample</string></value></member>
                    <member><name>count</name><value><int>3</int></value></member>
                    </struct></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            String member = "/methodResponse/params/param/value/struct/member";
            assertEquals("sample", this.text(xml, member + "[name='label']/value/string"));
            assertEquals("3", this.text(xml, member + "[name='count']/value/*"));
        }
    }

    @Test(timeout = 15000)
    public void omittedParamsCallsAZeroArgumentMethod() throws Exception {
        // 无参方法允许省略 params。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.current</methodName></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("ready", this.text(xml, "/methodResponse/params/param/value/string"));
        }
    }

    @Test(timeout = 15000)
    public void businessErrorsReturnAStandardFault() throws Exception {
        // 业务异常的错误码和消息写入标准 fault struct。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.fail</methodName><params>
                    <param><value><string>business rejected</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            String member = "/methodResponse/fault/value/struct/member";
            assertEquals("42", this.text(xml, member + "[name='faultCode']/value/*"));
            assertEquals("business rejected", this.text(xml, member + "[name='faultString']/value/string"));
            assertEquals("0", this.text(xml, "count(/methodResponse/params)"));
        }
    }

    @Test(timeout = 15000)
    public void unknownMethodReturnsAFault() throws Exception {
        // 未发布的方法无法从协议入口调用。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.missing</methodName></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32601", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void wrongParameterCountReturnsAFault() throws Exception {
        // 错误参数数量在进入业务之前报告。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.subtract</methodName><params>
                    <param><value><int>7</int></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32602", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void wrongParameterTypeReturnsAFault() throws Exception {
        // 字符串不会被静默转换为方法声明的 int。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.subtract</methodName><params>
                    <param><value><string>7</string></value></param>
                    <param><value><int>5</int></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32602", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void malformedXmlReturnsAParseFault() throws Exception {
        // XML 文档不完整时返回可读 fault。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.current</methodName>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32700", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void unexpectedEnvelopeReturnsAParseFault() throws Exception {
        // HTTP 中的 XML 必须是 methodCall。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <other><methodName>demo.current</methodName></other>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32700", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void nilIsRejectedUnlessExplicitlyConfigured() throws Exception {
        // 默认配置保留 XML-RPC 核心类型边界，不接受 nil 扩展。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对端直接发送标准 XML，避免用插件编解码器验证自己的输出。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><nil/></value></param></params></methodCall>
                    """;
            HttpRequest request = this.post(port, body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析线上响应，核对本场景的业务结果或 fault。
            assertEquals(200, response.statusCode());
            Document xml = this.parse(response.body());
            assertEquals("-32700", this.faultCode(xml));
        }
    }

    @Test(timeout = 15000)
    public void explicitlyEnabledNilRoundTripsANullValue() throws Exception {
        // nil 是显式启用的扩展，两端通过同一 HTTP 端点交换空值。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc").option("allowNil", true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 原始报文直接使用扩展标签，服务返回 null 后也编码为 nil。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><nil/></value></param></params></methodCall>
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());
            Document xml = this.parse(response.body());
            assertEquals(200, response.statusCode());
            assertEquals("1", this.text(xml, "count(/methodResponse/params/param/value/nil)"));
        }
    }

    @Test(timeout = 15000)
    public void externalEntityDeclarationsNeverFetchRemoteData() throws Exception {
        // 独立 HTTP 对端记录实体读取，安全验证不依赖本地敏感文件。
        AtomicInteger fetched = new AtomicInteger();
        HttpServer entityServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entityServer.createContext("/entity", exchange -> {
            fetched.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        entityServer.start();
        try {
            MetConfiguration configuration = this.configuration();
            try (MetContext server = configuration.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                        .aliasName("xmlrpc", "demo") //
                        .register();

                // methodCall 试图引入远程实体，解析器必须在读取外部地址之前拒绝。
                String location = "http://127.0.0.1:" + entityServer.getAddress().getPort() + "/entity";
                String body = """
                        <!DOCTYPE methodCall [<!ENTITY external SYSTEM "%s">]>
                        <methodCall><methodName>demo.echo</methodName><params>
                        <param><value><string>&external;</string></value></param>
                        </params></methodCall>
                        """.formatted(location);
                int port = server.getListenAddress("xmlrpc").getPort();
                HttpRequest request = this.post(port, body);
                HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());

                // 业务没有执行或泄露实体内容，对端从未收到读取请求。
                assertEquals(200, response.statusCode());
                assertEquals("-32700", this.faultCode(this.parse(response.body())));
                assertEquals(0, fetched.get());
            }
        } finally {
            entityServer.stop(0);
        }
    }

    @Test(timeout = 15000)
    public void nonPostHttpMethodIsRejected() throws Exception {
        // XML-RPC 的普通 HTTP 入口只接受 POST。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 对已挂载端点执行 GET，由协议返回明确的 HTTP 方法错误。
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
        }
    }

    @Test(timeout = 15000)
    public void incompatibleContentTypeIsRejected() throws Exception {
        // 同端口中的其它正文类型不能误入 XML-RPC 编解码。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 路径匹配成功，但 JSON 正文必须在 HTTP 媒体类型层面拒绝。
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString("{}")) //
                    .build();
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(415, response.statusCode());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }

    private HttpRequest post(int port, String body) {
        URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "text/xml; charset=UTF-8") //
                .POST(HttpRequest.BodyPublishers.ofString(body)) //
                .build();
    }

    private Document parse(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
    }

    private String text(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }

    private String faultCode(Document document) throws Exception {
        String member = "/methodResponse/fault/value/struct/member";
        return this.text(document, member + "[name='faultCode']/value/*");
    }
}
