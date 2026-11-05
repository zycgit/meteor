/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.scenario;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.rpc.RpcServer;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.shared.storage.domain.EntApplicationCatalogData;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

public class ConsoleIntegrationTest {
    @TempDir
    public               Path   directory;
    private static final String ROUTE = "<route><return value='addresses'/></route>";

    @MetService(group = "commerce", version = "v1")
    public interface Echo {
        String echo(String value);
    }

    @MetService(group = "commerce", version = "v1")
    public interface NextEcho {
        String echo(String value);
    }

    @Test
    public void injectedSettingsKeepDefaultUnitAndCookieFlags() throws Exception {
        // 默认单元和 Cookie 参数由各自消费者接收，不经过独立配置对象。
        String settings = console(this.directory);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);
            var response = api.send("auth/login", "POST", Map.of("username", "admin", "password", "test-password"));
            assertEquals(200, response.statusCode(), response.body());
            assertFalse(response.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));
            var session = api.get("auth/session");
            assertTrue(session.get("securityEnabled").asBoolean());
            assertEquals("admin", session.get("userName").asText());
            assertEquals("default", session.get("localUnit").asText());
        }
    }

    @Test
    public void injectedSettingsKeepUtf8AccountsAndPasswordColons() throws Exception {
        // 各 Bean 分别接收配置，并通过实际登录验证 UTF-8 账号、冒号密码和 Cookie 属性。
        Path dataDirectory = this.directory.resolve("connections");
        String settings = console(this.directory, Map.of("data.directory", dataDirectory.toString(), "meteor.unit", "east", "session.secureCookie", "true", "security.account", "运维:deployment:password"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);
            var response = api.send("auth/login", "POST", Map.of("username", "运维", "password", "deployment:password"));
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));
            var session = JsonUtils.readTree(response.body());
            assertEquals("运维", session.get("userName").asText());
            assertEquals("east", session.get("localUnit").asText());
            assertTrue(Files.isDirectory(dataDirectory));
        }
    }

    @Test
    public void securityIsDisabledByDefaultAndIgnoresUnusedAccount() throws Exception {
        // 不设置 security.enable，保留无效账号文本；默认关闭登录时不解析账号。
        Path settings = this.directory.resolve("meteor.properties");
        Files.writeString(settings, "hasor.boot.web.connectors.http.port=0\nsecurity.account=unused\n" + "data.directory=" + this.directory.resolve("connections") + "\n");
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings.toUri().toString());
        try (BootApplication server = boot.start()) {
            var session = new Api(server).get("auth/session");
            assertFalse(session.get("securityEnabled").asBoolean());
            assertEquals("", session.get("userName").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "admin:", "missing-separator", ":password" })
    public void enabledSecurityRejectsInvalidInjectedAccounts(String account) throws Exception {
        // 账号校验由 SessionService 初始化完成，错误配置必须阻止容器启动。
        String settings = console(this.directory, Map.of("security.account", account));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        IllegalStateException failure = assertThrows(IllegalStateException.class, boot::start);
        assertTrue(failure.getMessage().contains("context failed to start"));
    }

    @Test
    public void invalidInjectedSecurityFlagDoesNotDisableAuthentication() throws Exception {
        // 注入字符串后严格校验开关，拼写错误不能静默关闭登录。
        String settings = console(this.directory, Map.of("security.enable", "flase"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        IllegalStateException failure = assertThrows(IllegalStateException.class, boot::start);
        assertTrue(failure.getMessage().contains("context failed to start"));
    }

    @Test
    public void httpDisabledStartsRpcWithoutHttpListener() throws Exception {
        // 占用 HTTP 端口，验证模块正常加载，但原生 HTTP 开关阻止创建监听端点。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Path settings = this.directory.resolve("meteor.properties");
        try (ServerSocket occupied = new ServerSocket(0)) {
            Files.writeString(settings, "hasor.boot.web.connectors.http.enabled=false\nrpc.port=0\nmanagement.token=remote-secret\n" + "data.directory=" + this.directory.resolve("remote") + "\n" + "storage.type=jdbc\nstorage.database=h2\nstorage.username=sa\nstorage.address=" + url + "\n" + "hasor.boot.web.connectors.http.port=" + occupied.getLocalPort() + "\n" + "security.enable=false\n");
            Boot boot = new Boot()//
                    .sources(ConsoleMain.class)//
                    .hconfigFile(settings.toUri().toString());
            try (BootApplication server = boot.start()) {
                assertEquals(-1, port(server));
                String address = server.getAppContext().getInstance(RpcServer.class).getAddress();
                assertTrue(address.startsWith("rsf://127.0.0.1:"), address);
                assertNotEquals(occupied.getLocalPort(), URI.create(address).getPort());
            }
        }
    }

    @Test
    public void closingHeadlessApplicationReleasesRpcListener() throws Exception {
        // 从统一入口启动，再关闭整个应用，RPC 端口应随应用释放。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Path settings = this.directory.resolve("meteor.properties");
        Files.writeString(settings, "hasor.boot.web.connectors.http.enabled=false\nrpc.port=0\nmanagement.token=remote-secret\n" + "data.directory=" + this.directory.resolve("remote") + "\n" + "storage.type=jdbc\nstorage.database=h2\nstorage.username=sa\nstorage.address=" + url + "\n");
        int port;
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings.toUri().toString());
        try (BootApplication server = boot.start()) {
            String address = server.getAppContext().getInstance(RpcServer.class).getAddress();
            port = URI.create(address).getPort();
        }
        try (ServerSocket released = new ServerSocket(port)) {
            assertEquals(port, released.getLocalPort());
        }
    }

    @Test
    public void registryRpcAndHttpCanRunTogether() throws Exception {
        // 注册中心 RPC 独立于 HTTP 开关；同一次标准 Boot 启动可同时提供两种入口。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        String settings = console(this.directory, Map.of("hasor.boot.web.connectors.http.enabled", "true", "storage.type", "jdbc", "storage.database", "h2", "storage.username", "sa", "storage.address", url, "rpc.port", "0", "management.token", "remote-secret"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);
            api.login();
            assertTrue(port(server) > 0);
            assertTrue(server.getAppContext().getInstance(RpcServer.class).getAddress().startsWith("rsf://127.0.0.1:"));
        }
    }

    @Test
    public void registryStartupFailureReleasesHttpListener() throws Exception {
        // RPC Bean 初始化失败时，Boot 应回滚整个 Web 上下文并释放监听端口。
        int httpPort;
        try (ServerSocket available = new ServerSocket(0)) {
            httpPort = available.getLocalPort();
        }
        String settings = console(this.directory, Map.of("hasor.boot.web.connectors.http.port", String.valueOf(httpPort), "storage.type", "unsupported", "storage.address", "unused", "management.token", "remote-secret"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings);
        IllegalStateException failure = assertThrows(IllegalStateException.class, boot::start);
        assertTrue(failure.getMessage().contains("context failed to start"));
        try (ServerSocket released = new ServerSocket(httpPort)) {
            assertEquals(httpPort, released.getLocalPort());
        }
    }

    @Test
    public void remoteConnectionAuthenticatesWithoutExposingStorageCredentials() throws Exception {
        // 启动独立的正式管理服务，控制台只持有 RPC 地址和管理凭据。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Path settings = this.directory.resolve("meteor.properties");
        Files.writeString(settings, "hasor.boot.web.connectors.http.enabled=false\nrpc.host=127.0.0.1\nrpc.port=0\nmanagement.token=remote-secret\n" + "data.directory=" + this.directory.resolve("remote") + "\n" + "storage.type=jdbc\nstorage.database=h2\nstorage.username=sa\nstorage.address=" + url + "\n");
        Boot bootA = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(settings.toUri().toString());
        Boot bootB = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory.resolve("console")));
        try (BootApplication remote = bootA.start(); //
             BootApplication server = bootB.start()) {
            Api api = new Api(server);
            api.login();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", "Remote 集群");
            profile.put("type", "remote");
            profile.put("address", remote.getAppContext().getInstance(RpcServer.class).getAddress());
            profile.put("managementToken", "wrong-secret");
            HttpResponse<String> denied = api.send("clusters/test", "POST", profile);
            assertEquals(403, denied.statusCode(), denied.body());
            assertFalse(denied.body().contains("remote-secret"));
            assertFalse(denied.body().contains("wrong-secret"));

            // 正确凭据可保存和读写；响应不包含凭据，编辑留空则保留已保存的值。
            profile.put("managementToken", "remote-secret");
            var saved = api.ok("clusters", "POST", profile);
            String id = saved.get("id").asText();
            assertTrue(saved.get("hasManagementToken").asBoolean());
            assertFalse(saved.has("managementToken"));
            assertFalse(saved.get("hasPassword").asBoolean());
            String query = "mode=registry&clusterId=" + id + "&scope=cluster";
            api.ok("metadata?" + query, "PUT", Map.of("values", Map.of("via", "remote", "weight", 42)));
            assertEquals(42, api.get("metadata?" + query).get("weight").asInt());
            profile.put("id", id);
            profile.remove("managementToken");
            assertTrue(api.ok("clusters", "POST", profile).get("hasManagementToken").asBoolean());
            api.ok("metadata?" + query, "DELETE", Map.of("keys", new String[] { "via", "weight" }));
            assertTrue(api.get("metadata?" + query).isEmpty());
        }
    }

    @Test
    public void clusterServiceDetailDoesNotChooseAnInstanceInterface() throws Exception {
        // 同一服务的两个实例声明不同 Java 接口，整体视图仍只返回目录摘要。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        var east = application(registry, "orders", "east", true);
        var west = application(registry, "orders", "west", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext first = east.buildContext(); //
             MetContext second = west.buildContext(); //
             BootApplication server = boot.start()) {
            String id = first.publisher().rsfService(Echo.class, value -> value).name("shared").register().getBindID();
            second.publisher().rsfService(NextEcho.class, value -> value).name("shared").register();
            first.start();
            second.start();
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("不同接口", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster;

            // 集群服务详情和列表不选择某个实例的完整描述或接口类型。
            var detail = api.get("service?" + scope + "&serviceId=" + encode(id));
            assertEquals(2, detail.at("/info/providerCount").asInt());
            assertFalse(detail.has("description"));
            assertFalse(detail.get("info").has("type"));
            assertFalse(detail.get("info").has("javaType"));
            var summary = api.get("services?" + scope).at("/items/0");
            assertFalse(summary.has("description"));
            assertFalse(summary.get("info").has("type"));
            assertFalse(summary.get("info").has("javaType"));
        }
    }

    @Test
    public void registryServiceDetailUsesTheSelectedInstanceDescription() throws Exception {
        // 相同服务 ID 的接口声明按实例隔离，不使用目录摘要推断 Java 类型。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        var east = application(registry, "orders", "east", true);
        var west = application(registry, "orders", "west", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext first = east.buildContext(); //
             MetContext second = west.buildContext(); //
             BootApplication server = boot.start()) {
            String id = first.publisher().rsfService(Echo.class, value -> value).name("shared").register().getBindID();
            second.publisher().rsfService(NextEcho.class, value -> value).name("shared").register();
            first.start();
            second.start();
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("实例接口", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster + "&serviceId=" + encode(id);

            // 两次点查各自读取确切实例的描述，身份仍来自服务摘要。
            var original = api.get("service?" + scope + "&instanceId=" + encode(first.getInstanceID()));
            var next = api.get("service?" + scope + "&instanceId=" + encode(second.getInstanceID()));
            assertEquals(Echo.class.getName(), original.at("/description/javaType").asText());
            assertEquals(NextEcho.class.getName(), next.at("/description/javaType").asText());
            assertEquals("Provider", next.at("/info/role").asText());
            assertFalse(next.get("description").has("role"));
            assertFalse(next.get("info").has("type"));
        }
    }

    @Test
    public void directServiceDetailUsesTheAddressInstanceDescription() throws Exception {
        // 直连 URL 中即使保留其它实例 ID，也只读取管理地址对应实例的声明。
        RegistryConfiguration firstRegistry = new RegistryConfiguration().setOnline(false).setManagementToken("test-token");
        RegistryConfiguration secondRegistry = new RegistryConfiguration().setOnline(false).setManagementToken("test-token");
        var firstConfig = application(firstRegistry, "original", "east", true);
        var secondConfig = application(secondRegistry, "replacement", "west", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext first = firstConfig.buildContext(); //
             MetContext second = secondConfig.buildContext(); //
             BootApplication server = boot.start()) {
            first.publisher().rsfService(Echo.class, value -> value).name("shared").register();
            var service = second.publisher().rsfService(NextEcho.class, value -> value).name("shared").register();
            String id = service.getBindID();
            first.start();
            second.start();
            Api api = new Api(server);
            api.login();
            String scope = "mode=direct&target=" + encode(second.getListenAddress("rsf").toString());
            api.managementToken("test-token");
            scope += "&serviceId=" + encode(id) + "&instanceId=" + encode(first.getInstanceID());

            // 描述从实际目标实例 ID 点查，且不恢复已经移除的摘要类型字段。
            var detail = api.get("service?" + scope);
            assertEquals(NextEcho.class.getName(), detail.at("/description/javaType").asText());
            assertEquals("Provider", detail.at("/info/role").asText());
            assertFalse(detail.get("description").has("role"));
            assertFalse(detail.get("info").has("type"));
        }
    }

    @Test
    public void instanceServiceDetailAllowsAnAbsentDescription() throws Exception {
        // 存储允许旧实例或非 Java 服务只登记运行摘要，不要求完整接口描述。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (var storage = registry.openStorage(); //
             BootApplication server = boot.start()) {
            EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
            instance.setInstanceId("external-instance");
            instance.setApplicationName("external-app");
            storage.registerInstance(instance);
            EntServiceRuntimeData service = new EntServiceRuntimeData();
            service.setServiceId("external-service");
            service.setName("external-service");
            service.setRole(MetServiceType.Provider);
            storage.registerService(instance.getInstanceId(), service);
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("无描述", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster + "&instanceId=" + instance.getInstanceId();

            // 描述缺失不影响服务状态与身份查询。
            var detail = api.get("service?" + scope + "&serviceId=" + service.getServiceId());
            assertTrue(detail.get("description").isNull());
            assertEquals(service.getServiceId(), detail.at("/info/serviceId").asText());
            assertEquals("Provider", detail.at("/info/role").asText());
            assertTrue(detail.get("enabled").asBoolean());
        }
    }

    @Test
    public void providerProtocolFilteringUsesEndpointProtocolName() throws Exception {
        // 两个提供者中仅一个挂载 Hprose，用协议名选择提供者仍返回完整端点。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        var east = application(registry, "orders", "east", true);
        east.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        var west = application(registry, "orders", "west", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext first = east.buildContext(); //
             MetContext second = west.buildContext(); //
             BootApplication server = boot.start()) {
            String id = first.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            second.publisher().rsfService(Echo.class, value -> value).register();
            first.start();
            second.start();
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("协议筛选", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster + "&serviceId=" + encode(id);

            // 协议查询参数保留自身语义，返回端点只使用 protocolName 表达协议身份。
            var members = api.get("service/members?" + scope + "&role=providers&protocol=hprose").get("items");
            assertEquals(1, members.size());
            assertEquals(first.getInstanceID(), members.at("/0/instance/instanceId").asText());
            Set<String> protocols = new HashSet<>();
            for (var endpoint : members.at("/0/endpoints")) {
                assertFalse(endpoint.has("protocol"));
                protocols.add(endpoint.get("protocolName").asText());
            }
            assertEquals(Set.of("rsf", "hprose"), protocols);
        }
    }

    @Test
    public void applicationDirectoryPagesPreserveOfflineNamesAndServerFiltering() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        try (var storage = registry.openStorage()) {
            // 应用目录独立存在，不需要伪造实例或服务来生成应用名称。
            for (int i = 0; i < 105; i++) {
                EntApplicationCatalogData application = new EntApplicationCatalogData();
                application.setApplicationName("archived-" + i);
                storage.saveApplication(application);
            }
        }
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("分页", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster;
            var first = api.get("applications?" + scope);
            assertEquals(100, first.get("items").size());
            assertFalse(first.get("nextCursor").isNull());
            var next = api.get("applications?" + scope + "&cursor=" + encode(first.get("nextCursor").asText()));
            assertEquals(5, next.get("items").size());
            assertTrue(next.get("nextCursor").isNull());
            Set<String> names = new HashSet<>();
            for (var item : first.get("items")) {
                assertEquals(1, item.size());
                assertTrue(names.add(item.get("name").asText()));
            }
            for (var item : next.get("items")) {
                assertTrue(names.add(item.get("name").asText()));
            }
            assertEquals(105, names.size());

            // 条件由 HTTP 传入提供者；没有实例也能查询最后创建的应用。
            var filtered = api.get("applications?" + scope + "&filter=ARCHIVED-104&limit=1");
            assertEquals("archived-104", filtered.at("/items/0/name").asText());
            assertTrue(filtered.get("nextCursor").isNull());
        }
    }

    @Test
    public void loginAndClusterProfilesSurviveRestartWithoutLeakingSecrets() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Path dataDirectory = this.directory;
        String config = console(dataDirectory);
        String clusterId;
        Boot bootA = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(config);
        try (BootApplication server = bootA.start()) {
            Api api = new Api(server);
            // 配置账号登录，首次启动没有连接；未认证不能读取任何集群信息。
            assertEquals(401, api.send("clusters", "GET", null).statusCode());
            var bad = Map.of("username", "admin", "password", "bad");
            assertEquals(401, api.send("auth/login", "POST", bad).statusCode());
            api.login();
            assertEquals("admin", api.get("auth/session").get("userName").asText());
            assertEquals(0, api.get("clusters").size());

            // 测试连接不保存；保存时再次验证实际表，返回值不含密码和 token。
            api.ok("clusters/test", "POST", profile("生产", url));
            assertEquals(0, api.get("clusters").size());
            var saved = api.ok("clusters", "POST", profile("生产", url));
            clusterId = saved.get("id").asText();
            assertFalse(saved.has("password"));
            assertFalse(saved.has("managementToken"));
            assertFalse(saved.get("hasManagementToken").asBoolean());
            assertEquals(1, api.get("clusters").size());

            // 退出注销会话，不只是移除浏览器页面状态。
            api.ok("auth/logout", "POST", Map.of());
            assertEquals(401, api.send("clusters", "GET", null).statusCode());
        }
        Boot bootB = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(config);
        try (BootApplication server = bootB.start()) {
            Api api = new Api(server);
            api.login();
            var existing = api.get("clusters").get(0);
            assertEquals(clusterId, existing.get("id").asText());
            // 编辑时不回传密钥，保留旧值；移除只修改本地连接文件。
            Map<String, Object> edit = new LinkedHashMap<>(profile("生产 · 新名称", url));
            edit.put("id", clusterId);
            edit.remove("password");
            edit.remove("managementToken");
            api.ok("clusters", "POST", edit);
            var disk = JsonUtils.readTree(Files.readString(dataDirectory.resolve("clusters.json")));
            assertFalse(disk.get(0).hasNonNull("managementToken"));
            api.ok("clusters?id=" + clusterId, "DELETE", null);
            assertEquals(0, api.get("clusters").size());
        }
    }

    @Test
    public void editingClusterReplacesWarmedConnectionWithoutChangingPreviousRegistry() throws Exception {
        // 两个注册中心保留独立元数据，观察者不受 Console 连接替换影响。
        String previousUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String replacementUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var previousRegistry = registry(previousUrl);
        var replacementRegistry = registry(replacementUrl);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (RegistryAdmin previous = previousRegistry.buildAdmin(); //
             RegistryAdmin replacement = replacementRegistry.buildAdmin(); //
             BootApplication server = boot.start()) {
            Map<String, Object> previousMetadata = Map.of("registry", "previous", "owner", "preserved");
            Map<String, Object> replacementMetadata = Map.of("registry", "replacement");
            previous.getClusterAdmin().putMeta(previousMetadata);
            replacement.getClusterAdmin().putMeta(replacementMetadata);
            Api api = new Api(server);
            api.login();

            // 先通过治理查询打开并缓存旧连接，避免只验证保存时的临时连接。
            String clusterId = api.ok("clusters", "POST", profile("原注册中心", previousUrl)).get("id").asText();
            String metadataPath = "metadata?mode=registry&scope=cluster&clusterId=" + clusterId;
            assertEquals(JsonUtils.valueToTree(previousMetadata), api.get(metadataPath));

            // 相同配置 ID 改用新地址，后续治理操作必须重新连接到新注册中心。
            Map<String, Object> edited = new LinkedHashMap<>(profile("新注册中心", replacementUrl));
            edited.put("id", clusterId);
            assertEquals(clusterId, api.ok("clusters", "POST", edited).get("id").asText());
            assertEquals(JsonUtils.valueToTree(replacementMetadata), api.get(metadataPath));
            api.ok(metadataPath, "PUT", Map.of("values", Map.of("changed", true)));
            Map<String, Object> updated = Map.of("registry", "replacement", "changed", true);
            assertEquals(JsonUtils.valueToTree(updated), api.get(metadataPath));
            assertEquals(updated, replacement.getClusterAdmin().fetchMeta());

            // 替换只释放旧的 Console 连接，不关闭独立观察者，也不污染旧数据。
            assertTrue(previous.getClusterAdmin().status().isReachable());
            assertEquals(previousMetadata, previous.getClusterAdmin().fetchMeta());
        }
    }

    @Test
    public void registryGovernanceAndUnitTargetsStayIsolatedAcrossClusters() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String otherUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var registry = registry(url);
        registry(otherUrl);
        var east = application(registry, "orders", "east", true);
        var west = application(registry, "orders", "west", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext a = east.buildContext(); //
             MetContext b = west.buildContext(); //
             BootApplication server = boot.start()) {
            String id = a.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            b.publisher().rsfService(Echo.class, value -> value).register();
            a.start();
            b.start();
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("主集群", url)).get("id").asText();
            String other = api.ok("clusters", "POST", profile("独立集群", otherUrl)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster;
            String service = scope + "&scope=service&serviceId=" + encode(id);
            String rule = service + "&type=SERVICE_ROUTE";
            RegistryAdmin admin = a.getExtension(RegistryAdmin.class);

            // 目录仅来自所选集群，列表支持多协议并保留两种成员关系。
            assertEquals(1, api.get("services?" + scope).get("items").size());
            assertEquals(0, api.get("services?mode=registry&clusterId=" + other).get("items").size());
            assertEquals(2, api.get("instances?" + scope).get("items").size());
            assertEquals(2, api.get("service/members?" + service + "&role=providers").get("items").size());
            assertEquals(0, api.get("service/members?" + service + "&role=consumers").get("items").size());

            // 全局、本服务、指定单元分别写入；不影响另一个单元或另一个集群。
            api.ok("rule?" + scope + "&scope=cluster&type=SERVICE_ROUTE", "PUT", Map.of("rule", ROUTE));
            api.ok("rule?" + rule + "&targetUnit=east", "PUT", Map.of("rule", ROUTE));
            assertEquals(ROUTE, admin.getServiceAdmin(id, a.getInstanceID()).fetchRule(MetRuleType.SERVICE_ROUTE));
            assertNull(admin.getServiceAdmin(id, b.getInstanceID()).fetchRule(MetRuleType.SERVICE_ROUTE));
            assertNull(admin.getServiceAdmin(id).fetchRule(MetRuleType.SERVICE_ROUTE));
            var unknownUnit = api.send("rule?" + rule + "&targetUnit=missing", "PUT", Map.of("rule", ROUTE));
            assertEquals(400, unknownUnit.statusCode());
            api.ok("rule?" + rule, "PUT", Map.of("rule", ROUTE));
            api.ok("rule/enabled?" + rule, "PUT", Map.of("enabled", false));
            assertFalse(api.get("rule?" + rule).get("enabled").asBoolean());
            assertEquals(ROUTE, api.get("rule?" + rule).get("rule").asText());
            assertEquals(400, api.send("rule?" + rule, "PUT", Map.of("rule", "<route/>")).statusCode());

            // 实例规则的单元选择只遍历当前应用，单元定向不覆盖其他单元。
            String instanceRule = scope + "&scope=instance&instanceId=" + encode(a.getInstanceID());
            instanceRule += "&type=METHOD_ROUTE";
            api.ok("rule?" + instanceRule + "&targetUnit=LOCAL", "PUT", Map.of("rule", ROUTE));
            assertEquals(ROUTE, admin.getInstanceAdmin(a.getInstanceID()).fetchRule(MetRuleType.METHOD_ROUTE));
            assertNull(admin.getInstanceAdmin(b.getInstanceID()).fetchRule(MetRuleType.METHOD_ROUTE));
            api.ok("rule?" + instanceRule + "&targetUnit=ALL", "PUT", Map.of("rule", ROUTE));
            assertEquals(ROUTE, admin.getInstanceAdmin(b.getInstanceID()).fetchRule(MetRuleType.METHOD_ROUTE));

            // 元信息按键合并，查询保留 JSON 类型；实例级阻断不使管理服务失联。
            api.ok("metadata?" + service, "PUT", Map.of("values", Map.of("team", "orders", "weight", 10)));
            api.ok("metadata?" + service, "PUT", Map.of("values", Map.of("weight", 20)));
            assertEquals("orders", api.get("metadata?" + service).get("team").asText());
            assertEquals(20, api.get("metadata?" + service).get("weight").asInt());
            api.ok("metadata?" + service, "DELETE", Map.of("keys", new String[] { "team" }));
            assertFalse(api.get("metadata?" + service).has("team"));
            String instance = scope + "&instanceId=" + encode(a.getInstanceID());
            api.ok("instance/block?" + instance, "PUT", Map.of("blocked", true));
            assertTrue(api.get("instance?" + instance).get("blocked").asBoolean());
            assertFalse(api.get("instance?" + instance).has("settings"));
            api.ok("instance/block?" + instance, "PUT", Map.of("blocked", false));

            // 服务全部离线后持久目录仍然存在。
            a.close();
            b.close();
            assertEquals(id, api.get("service?" + service).at("/info/serviceId").asText());
        }
    }

    @Test
    public void instanceIdentityAndOnlineStatusFollowRegistrationLifecycle() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var config = application(registry(url), "orders", "east", false);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext target = config.buildContext(); //
             BootApplication server = boot.start()) {
            // 仅出站的应用同样登记真实的 IP 和当前进程号。
            target.start();
            Api api = new Api(server);
            api.login();
            String cluster = api.ok("clusters", "POST", profile("实例状态", url)).get("id").asText();
            String scope = "mode=registry&clusterId=" + cluster + "&instanceId=" + target.getInstanceID();
            var snapshot = api.get("instance?" + scope);
            assertTrue(snapshot.get("online").asBoolean());
            assertEquals(target.getSettings().getBindAddress(), snapshot.at("/instance/ipAddress").asText());
            assertEquals(ProcessHandle.current().pid(), snapshot.at("/instance/processId").asLong());

            // 业务被阻断不代表进程离线。
            api.ok("instance/block?" + scope, "PUT", Map.of("blocked", true));
            var blocked = api.get("instance?" + scope);
            assertTrue(blocked.get("blocked").asBoolean());
            assertTrue(blocked.get("online").asBoolean());

            // 注销运行记录后显示离线，不把保留的治理配置当作在线实例。
            target.close();
            var offline = api.get("instance?" + scope);
            assertFalse(offline.get("online").asBoolean());
            assertEquals(target.getInstanceID(), offline.at("/instance/instanceId").asText());
            var offlineServices = api.get("services?" + scope);
            assertEquals(0, offlineServices.get("items").size());
            assertEquals(snapshot.at("/instance/ipAddress"), offline.at("/instance/ipAddress"));
            assertEquals(snapshot.at("/instance/processId"), offline.at("/instance/processId"));
            var instances = api.get("instances?mode=registry&clusterId=" + cluster).get("items");
            assertEquals(1, instances.size());
            assertFalse(instances.get(0).get("online").asBoolean());
            var applications = api.get("applications?mode=registry&clusterId=" + cluster).get("items");
            assertEquals("orders", applications.get(0).get("name").asText());
        }
    }

    @Test
    public void directManagementUsesRpcAndReportsUnsupportedOperations() throws Exception {
        RegistryConfiguration offline = new RegistryConfiguration().setOnline(false);
        offline.setManagementToken("test-token");
        var config = application(offline, "direct", "default", true);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (MetContext target = config.buildContext(); //
             BootApplication server = boot.start()) {
            String id = target.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            target.start();
            Api api = new Api(server);
            api.login();
            String scope = "mode=direct&target=" + encode(target.getListenAddress("rsf").toString());
            api.managementToken("test-token");
            String service = scope + "&scope=service&serviceId=" + encode(id);
            String rule = service + "&type=SERVICE_ROUTE";
            // 没有连接注册中心，通过不同上下文的网络 RPC 管理目标应用。
            assertEquals(0, api.get("clusters").size());
            assertEquals(target.getInstanceID(), api.get("instance?" + scope).at("/instance/instanceId").asText());
            assertTrue(api.get("instance?" + scope).get("online").asBoolean());
            api.ok("rule?" + rule, "PUT", Map.of("rule", ROUTE));
            assertEquals(ROUTE, target.getUpdater().serviceRoute(id));
            assertEquals(409, api.send("rule/enabled?" + rule, "PUT", Map.of("enabled", false)).statusCode());
            api.ok("metadata?" + service, "PUT", Map.of("values", Map.of("owner", "direct")));
            assertEquals("direct", api.get("metadata?" + service).get("owner").asText());
            api.ok("instance/block?" + scope, "PUT", Map.of("blocked", true));
            assertTrue(api.get("instance?" + scope).get("blocked").asBoolean());
            api.ok("instance/block?" + scope, "PUT", Map.of("blocked", false));
            target.close();
            assertEquals(502, api.send("instance?" + scope, "GET", null).statusCode());
        }
    }

    @Test
    public void httpBoundaryChecksOriginBodiesAndServesPackagedAssets() throws Exception {
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (BootApplication server = boot.start()) {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
            URI root = URI.create("http://127.0.0.1:" + port(server));
            String credentials = JsonUtils.writeValueAsString(Map.of("username", "admin", "password", "test-password"));

            // 不允许跨站登录；会话通过 HttpOnly Cookie 传递，不使用浏览器存储 token。
            URI loginUri = root.resolve("/api/auth/login");
            HttpRequest.Builder foreignBuilder = HttpRequest.newBuilder(loginUri)//
                    .header("Origin", "https://elsewhere.invalid")//
                    .header("Content-Type", "application/json");
            HttpRequest foreign = foreignBuilder.POST(HttpRequest.BodyPublishers.ofString(credentials)).build();
            assertEquals(403, client.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest.Builder loginBuilder = HttpRequest.newBuilder(loginUri)//
                    .header("Content-Type", "application/json");
            HttpRequest login = loginBuilder.POST(HttpRequest.BodyPublishers.ofString(credentials)).build();
            var signedIn = client.send(login, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signedIn.statusCode());
            String cookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly"));
            assertTrue(cookie.contains("SameSite=Strict"));

            // 语法错误和媒体类型错误由 HTTP 层明确拒绝，不进入注册中心。
            URI clustersUri = root.resolve("/api/clusters");
            HttpRequest.Builder malformedBuilder = HttpRequest.newBuilder(clustersUri)//
                    .header("Content-Type", "application/json");
            HttpRequest malformed = malformedBuilder.POST(HttpRequest.BodyPublishers.ofString("{")).build();
            assertEquals(400, client.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest.Builder wrongTypeBuilder = HttpRequest.newBuilder(clustersUri)//
                    .header("Content-Type", "text/plain");
            HttpRequest wrongType = wrongTypeBuilder.POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(415, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());

            // 页面和静态资源同源部署，其他路径不暴露本地连接文件。
            HttpRequest page = HttpRequest.newBuilder(root).build();
            var rendered = client.send(page, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rendered.statusCode());
            assertTrue(rendered.body().contains("/assets/"));
            HttpRequest secret = HttpRequest.newBuilder(root.resolve("/data/clusters.json")).build();
            assertEquals(404, client.send(secret, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    public void connectionAndHttpFailuresRemainVisibleWithoutSavingBrokenProfiles() throws Exception {
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console(this.directory));
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);
            api.login();
            // JDBC 的缺表异常按实际失败反馈，不伪装为超时或测试成功。
            String missing = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
            var failed = api.send("clusters/test", "POST", profile("缺表", missing));
            assertEquals(502, failed.statusCode(), failed.body());
            assertTrue(failed.body().toLowerCase().contains("table"));
            assertEquals(0, api.get("clusters").size());
            assertEquals(400, api.send("clusters", "POST", profile("缺地址", "")).statusCode());
            assertEquals(400, api.send("instance?mode=direct&target=bad", "GET", null).statusCode());
            assertEquals(404, api.send("unknown?mode=direct", "GET", null).statusCode());
            assertEquals(400, api.send("instance?mode=direct&mode=registry", "GET", null).statusCode());
            assertEquals(413, api.send("clusters", "POST", Map.of("data", "x".repeat(1048576))).statusCode());
        }
    }
}
