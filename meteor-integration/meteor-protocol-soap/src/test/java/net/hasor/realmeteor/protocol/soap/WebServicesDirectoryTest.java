/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.example.protocol.soap.SignalService;
import net.hasor.example.protocol.soap.SignalServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesDirectoryTest {
    private static final Pattern LINKS = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    @Test(timeout = 20000)
    public void directoryListsPublishedServicesAndTheirUsableWsdlLinks() throws Exception {
        // 用户在 SOAP 协议配置中同时启用目录并指定完整 HTTP 路径。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "SignalService").protocol("soap").register();

            // 从真实 HTTP 监听读取服务目录，检查两个服务的调用地址和 WSDL 链接。
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> response = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/html"));
            assertTrue(contentType, StringUtils.containsIgnoreCase(contentType, "charset=utf-8"));
            String html = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(html, html.contains("EchoService"));
            assertTrue(html, html.contains("SignalService"));
            Set<URI> links = this.links(directory, html);
            URI echoEndpoint = this.endpoint(context, "/services/EchoService");
            URI signalEndpoint = this.endpoint(context, "/services/SignalService");
            URI echoWsdl = URI.create(echoEndpoint + "?wsdl");
            URI signalWsdl = URI.create(signalEndpoint + "?wsdl");
            assertTrue(html, html.contains(echoEndpoint.getPath()));
            assertTrue(html, html.contains(signalEndpoint.getPath()));
            assertTrue(html, links.contains(echoWsdl));
            assertTrue(html, links.contains(signalWsdl));

            // 跟随目录中公开的两个契约地址，确认链接确实指向当前服务。
            HttpResponse<byte[]> echo = http.send(this.get(echoWsdl), HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> signal = http.send(this.get(signalWsdl), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, echo.statusCode());
            assertEquals(200, signal.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void directoryIsDisabledByDefault() throws Exception {
        // 保持 SOAP 协议的默认配置，发布服务不会隐式开启目录。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();

            // 协议根地址仍不提供服务列表。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, response.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void directoryPathAloneDoesNotEnableDirectory() throws Exception {
        // 仅声明地址，未打开目录开关。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryPath", "/services/index");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 预留地址不会公开空目录或服务信息。
            URI directory = this.endpoint(context, "/services/index");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, response.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void directoryEnabledWithoutPathDoesNotPublishDirectory() throws Exception {
        // 仅打开开关，未指定目录地址，不会自动占用协议根路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 两个配置项必须同时具备才会提供目录。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, response.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void explicitFalseKeepsConfiguredDirectoryPrivate() throws Exception {
        // 保留目录路径但显式关闭开关。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", false).option("directoryPath", "/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 关闭状态下，目录地址不能被访问。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, response.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void configuredChildPathIsTheOnlyDirectoryAddress() throws Exception {
        // 目录使用 context 下的独立子路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true).option("directoryPath", "/services/index");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();

            // 只有指定地址返回目录；链接仍指向服务的实际发布路径。
            HttpClient http = this.http();
            URI directory = this.endpoint(context, "/services/index");
            HttpResponse<byte[]> response = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            assertTrue(html, links.contains(this.endpoint(context, "/services/EchoService?wsdl")));
            URI root = this.endpoint(context, "/services");
            HttpResponse<byte[]> absent = http.send(this.get(root), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, absent.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void directoryUsesConfiguredPublicBaseForServiceAddresses() throws Exception {
        // 反向代理部署通过 publicBaseUrl 声明外部可访问的地址前缀。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true).option("directoryPath", "/services") //
                .option("publicBaseUrl", "https://soap.example.test/gateway/");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();

            // 目录使用外部前缀拼接服务路径，不能返回内部监听地址或重复斜杠。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            String publicEndpoint = "https://soap.example.test/gateway/services/EchoService";
            assertTrue(html, html.contains(publicEndpoint));
            assertTrue(html, links.contains(URI.create(publicEndpoint + "?wsdl")));
        }
    }

    @Test(timeout = 20000)
    public void directoryListsSelectedContextsAndExactServicePaths() throws Exception {
        // 一个 SOAP 协议挂载多个 context，目录聚合这个配置中的全部实际服务地址。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").addContextPath("/private") //
                .option("directoryEnabled", true).option("directoryPath", "/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .contextPathByPrefix("soap", "/services", "/private").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "SignalService").protocol("soap") //
                    .contextPathByExact("soap", "/api/signal").register();

            // 多 context 服务分别列出真实地址，精确路径不能重复附加别名或 context。
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> response = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            URI privateWsdl = this.endpoint(context, "/private/EchoService?wsdl");
            URI exactWsdl = this.endpoint(context, "/api/signal?wsdl");
            assertTrue(html, links.contains(this.endpoint(context, "/services/EchoService?wsdl")));
            assertTrue(html, links.contains(privateWsdl));
            assertTrue(html, html.contains("/api/signal"));
            assertTrue(html, links.contains(exactWsdl));
            assertFalse(html, html.contains("/services/SignalService"));
            assertFalse(html, html.contains("/api/signal/SignalService"));

            // 额外 context 和精确路径的契约都能通过网络读取。
            HttpRequest privateRequest = this.get(privateWsdl);
            HttpRequest exactRequest = this.get(exactWsdl);
            HttpResponse<byte[]> privateDefinition = http.send(privateRequest, HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> exactDefinition = http.send(exactRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, privateDefinition.statusCode());
            assertEquals(200, exactDefinition.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void nestedContextHidesTheDefaultServiceEndpointFromDirectory() throws Exception {
        // 更具体的子 context 占用 Echo 默认地址；另一个默认服务仍然可见。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").addContextPath("/services/EchoService") //
                .option("directoryEnabled", true).option("directoryPath", "/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "SignalService").protocol("soap").register();

            // 目录不能列出被子挂载遮蔽的 Echo，未受影响的 Signal 仍有契约链接。
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> response = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            assertFalse(html, html.contains("EchoService"));
            assertTrue(html, links.contains(this.endpoint(context, "/services/SignalService?wsdl")));

            // 真实 HTTP 路由命中子 context 后，默认 Echo 的契约确实不可访问。
            HttpRequest hiddenRequest = this.get(this.endpoint(context, "/services/EchoService?wsdl"));
            HttpResponse<byte[]> hidden = http.send(hiddenRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, hidden.statusCode());
        }
    }

    @Test(timeout = 20000)
    public void exactServicePathReplacesTheCoveredProviderInDirectory() throws Exception {
        // Signal 的精确路径覆盖 Echo 的默认调用地址，服务别名仍各自独立。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "SignalService").protocol("soap") //
                    .contextPathByExact("soap", "/services/EchoService").register();

            // 目录保留实际可达的 Signal，不能把同一个地址继续归到 Echo 提供者。
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> response = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            URI actualWsdl = this.endpoint(context, "/services/EchoService?wsdl");
            assertTrue(html, html.contains(">SignalService<"));
            assertFalse(html, html.contains(">EchoService<"));
            assertEquals(Set.of(actualWsdl), links);

            // 跟随该地址得到 Signal 的真实契约，说明列表与精确路由的提供者一致。
            HttpRequest actualRequest = this.get(actualWsdl);
            HttpResponse<byte[]> definition = http.send(actualRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, definition.statusCode());
            String wsdl = new String(definition.body(), StandardCharsets.UTF_8);
            assertTrue(wsdl, wsdl.contains("urn:hasor:meteor:soap:SignalService"));
            assertFalse(wsdl, wsdl.contains("urn:hasor:meteor:soap:EchoService"));
        }
    }

    @Test(timeout = 20000)
    public void explicitContextSelectionDoesNotInventDefaultContextLinks() throws Exception {
        // 服务仅选择 private context，目录仍挂载在默认 services context。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").addContextPath("/private") //
                .option("directoryEnabled", true).option("directoryPath", "/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .contextPathByPrefix("soap", "/private").register();

            // 目录只能列出显式选择的地址，不能把默认 context 当成额外发布点。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            assertTrue(html, links.contains(this.endpoint(context, "/private/EchoService?wsdl")));
            assertFalse(html, html.contains("/services/EchoService"));
        }
    }

    @Test(timeout = 20000)
    public void consumerDeclarationsAreExcludedFromDirectory() throws Exception {
        // 同一个容器既发布 SOAP 服务，也订阅另一个 SOAP 契约。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher().rsfService(SignalService.class) //
                    .aliasName("soap", "ConsumerSignal").protocol("soap").register();

            // 目录包含本地提供者，不能将消费者描述伪装成可调用服务。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(html, html.contains("EchoService"));
            assertFalse(html, html.contains("ConsumerSignal"));
        }
    }

    @Test(timeout = 20000)
    public void shadowProvidersAreExcludedFromDirectory() throws Exception {
        // 隐藏服务与正常提供者同时声明在 SOAP 协议上。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "ShadowSignal").protocol("soap").asShadow().register();

            // 开启目录不能让隐藏服务出现在公开列表中。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(html, html.contains("EchoService"));
            assertFalse(html, html.contains("ShadowSignal"));
        }
    }

    @Test(timeout = 20000)
    public void providersBoundOnlyToAnotherProtocolAreExcludedFromDirectory() throws Exception {
        // 容器同时提供 SOAP 和 RSF，另一个协议的服务也有 SOAP 别名。
        MetConfiguration configuration = this.directoryConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            context.publisher() //
                    .rsfService(SignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "RsfSignal").protocol("rsf").register();

            // 目录按实际协议选择过滤，不能仅凭 SOAP 别名收集服务。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(html, html.contains("EchoService"));
            assertFalse(html, html.contains("RsfSignal"));
        }
    }

    @Test(timeout = 20000)
    public void servicesRegisteredAfterTheFirstDirectoryRequestAppearImmediately() throws Exception {
        // 先启动空的 SOAP 容器并读取目录，形成动态发布前的观察结果。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> before = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, before.statusCode());
            assertFalse(new String(before.body(), StandardCharsets.UTF_8).contains("EchoService"));

            // 目录已被访问后再通过公开入口发布服务，不重启容器。
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            HttpResponse<byte[]> after = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, after.statusCode());
            String html = new String(after.body(), StandardCharsets.UTF_8);
            Set<URI> links = this.links(directory, html);
            assertTrue(html, links.contains(this.endpoint(context, "/services/EchoService?wsdl")));
        }
    }

    @Test(timeout = 20000)
    public void headReturnsDirectoryHeadersWithoutTheHtmlBody() throws Exception {
        // 发布服务，使目录正文长度可以与 HEAD 声明值进行比较。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register();
            URI directory = this.endpoint(context, "/services");
            HttpClient http = this.http();
            HttpResponse<byte[]> get = http.send(this.get(directory), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode());

            // 同一路径的 HEAD 保留 GET 元数据，但网络正文为空。
            HttpRequest request = HttpRequest.newBuilder(directory).timeout(Duration.ofSeconds(15)) //
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> head = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals(get.body().length, head.headers().firstValueAsLong("content-length").orElse(-1));
            String getContentType = get.headers().firstValue("content-type").orElse("");
            assertEquals(getContentType, head.headers().firstValue("content-type").orElse(""));
        }
    }

    @Test(timeout = 20000)
    public void postToDirectoryIsRejectedWithAllowedReadMethods() throws Exception {
        // 目录地址只提供读取服务列表的能力。
        MetConfiguration configuration = this.directoryConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // POST 不进入 SOAP 业务处理，返回标准方法不支持响应。
            URI directory = this.endpoint(context, "/services");
            HttpRequest request = HttpRequest.newBuilder(directory).timeout(Duration.ofSeconds(15)) //
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, response.statusCode());
            assertEquals("GET, HEAD", response.headers().firstValue("allow").orElse(""));
        }
    }

    @Test(timeout = 20000)
    public void serviceEndpointCannotOccupyTheDirectoryPath() throws Exception {
        // 目录地址与使用默认 context 和别名组合得到的服务地址相同。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true).option("directoryPath", "/services/EchoService");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 冲突在发布时直接报告，不能等到请求进入后才随机选择响应。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap").register());
            assertTrue(failure.getMessage(), failure.getMessage().contains("/services/EchoService"));
        }
    }

    @Test(timeout = 20000)
    public void exactServiceEndpointCannotOccupyTheDirectoryPath() throws Exception {
        // 服务显式精确路径与目录地址重叠，别名本身没有重叠。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true).option("directoryPath", "/services/index");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 精确路径必须执行同一冲突检查，不能遮蔽目录端点。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .contextPathByExact("soap", "/services/index").register());
            assertTrue(failure.getMessage(), failure.getMessage().contains("/services/index"));

            // 失败发布不能在当前目录中留下可见服务。
            URI directory = this.endpoint(context, "/services/index");
            HttpRequest request = this.get(directory);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("EchoService"));
        }
    }

    private MetConfiguration configuration() {
        return new MetConfiguration().setDefaultProtocol("soap");
    }

    private MetConfiguration directoryConfiguration() {
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("directoryEnabled", true).option("directoryPath", "/services");
        return configuration;
    }

    private URI endpoint(MetContext context, String path) {
        return URI.create("http://127.0.0.1:" + context.bindAddress("soap").getPort() + path);
    }

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
    }

    private Set<URI> links(URI address, String html) {
        Set<URI> links = new HashSet<>();
        Matcher matcher = LINKS.matcher(html);
        while (matcher.find()) {
            links.add(address.resolve(matcher.group(1)));
        }
        return links;
    }
}
