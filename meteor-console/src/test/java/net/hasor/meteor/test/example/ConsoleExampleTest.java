/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.example;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.boot.web.WebServer;
import net.hasor.cobble.StringUtils;
import net.hasor.core.AppContext;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.component.provider.jdbc.JdbcProviderFactory;
import net.hasor.meteor.console.component.provider.nacos.NacosProviderFactory;
import net.hasor.meteor.console.component.provider.remote.RemoteProviderFactory;
import net.hasor.meteor.console.component.provider.zk.ZookeeperProviderFactory;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.example.ConsumerMain;
import net.hasor.meteor.example.ConsoleLauncher;
import net.hasor.meteor.example.ProviderMain;
import net.hasor.meteor.example.api.CalculatorService;
import net.hasor.meteor.example.api.EchoService;
import net.hasor.meteor.example.api.StatusService;
import net.hasor.meteor.example.consumer.ServiceConsumer;
import net.hasor.meteor.example.provider.CalculatorServiceImpl;
import net.hasor.meteor.example.provider.EchoServiceImpl;
import net.hasor.meteor.example.provider.StatusServiceImpl;
import net.hasor.meteor.registry.RegistryAdmin;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 验证示例自身的 Boot 装配、角色隔离和经注册发现后的真实调用。 */
public class ConsoleExampleTest {
    @TempDir
    public Path temporary;

    private static final Set<String> PROTOCOLS = Set.of("rsf", "dubbo", "thrift", "oncrpc", "hprose", "jsonrpc", "xmlrpc", "hessian");
    private static final Set<Class<? extends ProviderFactory>> FACTORIES = Set.of(JdbcProviderFactory.class,
            NacosProviderFactory.class, RemoteProviderFactory.class, ZookeeperProviderFactory.class);

    @ParameterizedTest(name = "Boot 提供者 → JDBC 注册中心 → 注入消费者：{0}")
    @ValueSource(strings = { "rsf", "jsonrpc", "hessian" })
    public void bootExamplesDiscoverAndCallAllServices(String protocol) throws Exception {
        // 每个协议场景使用独立 H2 存储；保留连接直到两个应用退出，避免跨场景残留。
        String database = "console_example_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + database);
        source.setUser("sa");
        try (Connection connection = source.getConnection(); //
             InputStream resource = getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql"); //
             InputStreamReader script = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
            Server registry = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifExists").start();
            try {
                String jdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + registry.getPort() + "/mem:" + database;
                Set<Integer> ports = new HashSet<>();
                Boot providerBoot = application(ProviderMain.class, "example-provider.properties", jdbcUrl, ports);
                Boot consumerBoot = application(ConsumerMain.class, "example-consumer.properties", jdbcUrl, ports)//
                        .property("meteor.default-protocol", protocol)//
                        .property("meteor.registry.management-protocols", protocol);
                // 消费端仅安装当前协议，地址选择不能回退到其他已启用的协议。
                for (String candidate : PROTOCOLS) {
                    String host = "";
                    if (StringUtils.equals(candidate, protocol)) {
                        host = "127.0.0.1";
                    }
                    consumerBoot.property("meteor.connectors." + candidate + ".host", host);
                }
                if (!StringUtils.equals(protocol, "rsf")) {
                    consumerBoot.property("meteor.connectors." + protocol + ".context", "/" + protocol);
                }

                // 直接加载示例 Module 和配置，发布及代理生成均由 meteor-hasor 完成。
                try (BootApplication provider = providerBoot.start(); //
                     BootApplication consumer = consumerBoot.start()) {
                    AppContext providerApp = provider.getAppContext();
                    AppContext consumerApp = consumer.getAppContext();
                    MetContext server = providerApp.getInstance(MetContext.class);
                    MetContext client = consumerApp.getInstance(MetContext.class);
                    assertEquals(PROTOCOLS, server.runProtocols());
                    assertEquals(Set.of(protocol), client.runProtocols());
                    assertEquals(protocol, client.getDefaultProtocol());

                    // 相同扫描范围只定义业务 API；提供者实现不会泄漏到消费者容器。
                    String providerScope = providerApp.getSettings().getString("hasor.loadPackages");
                    String consumerScope = consumerApp.getSettings().getString("hasor.loadPackages");
                    assertEquals("net.hasor.meteor.example", providerScope);
                    assertEquals("net.hasor.meteor.example", consumerScope);
                    assertNull(providerApp.getBindInfo(ConsoleMain.class));
                    assertNull(consumerApp.getBindInfo(ConsoleMain.class));
                    for (Class<? extends ProviderFactory> factory : FACTORIES) {
                        assertNull(providerApp.getBindInfo(factory));
                        assertNull(consumerApp.getBindInfo(factory));
                    }
                    assertTrue(providerApp.findBindingBean(ServiceConsumer.class).isEmpty());
                    assertTrue(consumerApp.findBindingBean(EchoServiceImpl.class).isEmpty());
                    assertTrue(consumerApp.findBindingBean(CalculatorServiceImpl.class).isEmpty());
                    assertTrue(consumerApp.findBindingBean(StatusServiceImpl.class).isEmpty());
                    RegistryAdmin registryAdmin = server.getExtension(RegistryAdmin.class);
                    String instanceId = server.getInstanceID();
                    for (Class<?> service : Set.of(EchoService.class, CalculatorService.class, StatusService.class)) {
                        MetBindInfo<?> published = server.getServiceInfo(service);
                        assertEquals(MetServiceType.Provider, published.getServiceType());
                        assertEquals(MetServiceType.Consumer, client.getServiceInfo(service).getServiceType());
                        // 未限定协议的服务，应在注册中心记录全部 8 个有效协议端点。
                        String[] protocols = registryAdmin.getServiceAdmin(published.getBindID(), instanceId)//
                                .info().getProtocols();
                        assertEquals(PROTOCOLS, Set.of(protocols), published.getBindName());
                    }

                    // JSON-RPC 与 Hessian 共享监听端口，接口挂载路径由各自配置区分。
                    int jsonRpcPort = server.getListenAddress("jsonrpc").getPort();
                    assertEquals(jsonRpcPort, server.getListenAddress("hessian").getPort());
                    assertDoesNotThrow(() -> consumerApp.getInstance(ServiceConsumer.class).call());
                }
            } finally {
                registry.stop();
            }
        }
    }

    @Test
    public void consoleLauncherUsesExampleScanScopeAndRegistersProductionWeb() throws Exception {
        // 启动器显式装配正式控制台，扫描范围仍只包含示例，HTTP 使用独立随机端口。
        Boot boot = new Boot().sources(ConsoleMain.class, ConsoleLauncher.class)//
                .hconfigFile("meteor.properties")//
                .property("hasor.loadPackages", "net.hasor.meteor.example")//
                .property("hasor.boot.web.connectors.http.port", 0)//
                .property("data.directory", this.temporary.resolve("console").toString());
        try (BootApplication application = boot.start()) {
            AppContext app = application.getAppContext();
            assertEquals("net.hasor.meteor.example", app.getSettings().getString("hasor.loadPackages"));
            for (Class<? extends ProviderFactory> factory : FACTORIES) {
                assertNotNull(app.getBindInfo(factory));
            }
            assertTrue(app.findBindingBean(ServiceConsumer.class).isEmpty());
            assertTrue(app.findBindingBean(EchoServiceImpl.class).isEmpty());
            assertNull(app.getInstance(MetContext.class).getServiceInfo(EchoService.class));

            // 经真实 HTTP 请求验证路由与 JSON 装配，控制台不会启动示例提供者或消费者。
            int port = app.getInstance(WebServer.class).getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/api/auth/session");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)).GET().build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertFalse(JsonUtils.readTree(response.body()).path("securityEnabled").asBoolean());
        }
    }

    private Boot application(Class<?> source, String configuration, String jdbcUrl, Set<Integer> ports)
            throws Exception {
        Boot boot = new Boot().sources(source)//
                .hconfigFile(configuration)//
                .property("example.jdbc.url", jdbcUrl)//
                .property("example.jdbc.username", "sa")//
                .property("example.jdbc.password", "")//
                .property("hasor.boot.web.connectors.http.enabled", false);

        // 非零端口让不同协议保持独立 Connector；两个 HTTP 协议明确共用一个端口。
        int sharedHttpPort = availablePort(ports);
        for (String protocol : PROTOCOLS) {
            int port = availablePort(ports);
            if (StringUtils.equals(protocol, "jsonrpc") || StringUtils.equals(protocol, "hessian")) {
                port = sharedHttpPort;
            }
            boot.property("meteor.connectors." + protocol + ".port", port);
        }
        return boot;
    }

    private int availablePort(Set<Integer> used) throws Exception {
        while (true) {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                int port = socket.getLocalPort();
                if (used.add(port)) {
                    return port;
                }
            }
        }
    }
}
