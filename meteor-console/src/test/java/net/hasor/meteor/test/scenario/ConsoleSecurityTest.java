/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.global.auth.AuthenticationException;
import net.hasor.meteor.console.global.error.ConsoleOperationException;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.service.ClusterService;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.registry.RegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 从配置和 HTTP 入口验证 JWT 会话与无需登录模式。 */
public class ConsoleSecurityTest {
    @TempDir
    public Path temporary;

    @Test
    public void jwtCookieRejectsTamperingAndRevokesOnlyCurrentSession() throws Exception {
        Path rootDirectory = this.temporary;
        String configuration = console(rootDirectory, Map.of("session.secureCookie", "true"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration);
        try (BootApplication server = boot.start()) {
            URI root = URI.create("http://127.0.0.1:" + port(server) + "/api/");

            // 登录得到 HttpOnly JWT，正文仅含身份与有效期，不携带密码。
            var credentials = Map.of("username", "admin", "password", "test-password");
            var login = send(root, "auth/login", "POST", "", credentials);
            assertEquals(200, login.statusCode());
            String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            assertTrue(setCookie.contains("Secure"));
            assertTrue(setCookie.contains("Max-Age=1800"));
            String cookie = setCookie.split(";", 2)[0];
            String[] jwt = cookie.substring(cookie.indexOf('=') + 1).split("\\.");
            assertEquals(3, jwt.length);
            var header = JsonUtils.readTree(Base64.getUrlDecoder().decode(jwt[0]));
            var claims = JsonUtils.readTree(Base64.getUrlDecoder().decode(jwt[1]));
            assertEquals("HS256", header.get("alg").asText());
            assertEquals("meteor-console", claims.get("iss").asText());
            assertEquals("admin", claims.get("sub").asText());
            assertFalse(claims.get("jti").asText().isEmpty());
            assertEquals(1800, claims.get("exp").asLong() - claims.get("iat").asLong());
            assertFalse(claims.has("password"));
            assertEquals(200, send(root, "clusters", "GET", cookie, null).statusCode());

            // 无签名、篡改正文和格式错误都明确返回 401。
            byte[] fakePayload = "{}".getBytes(StandardCharsets.UTF_8);
            String fakeBody = Base64.getUrlEncoder().withoutPadding().encodeToString(fakePayload);
            String tampered = "meteor_session=" + jwt[0] + "." + fakeBody + "." + jwt[2];
            assertEquals(401, send(root, "clusters", "GET", tampered, null).statusCode());
            String unsigned = "meteor_session=eyJhbGciOiJub25lIn0." + jwt[1] + ".";
            assertEquals(401, send(root, "clusters", "GET", unsigned, null).statusCode());
            assertEquals(401, send(root, "clusters", "GET", "meteor_session=invalid", null).statusCode());

            // 再次登录只轮换当前 Cookie；另一个浏览器的登录保持有效。
            var independent = send(root, "auth/login", "POST", "", credentials);
            String otherCookie = independent.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var rotated = send(root, "auth/login", "POST", cookie, credentials);
            String newCookie = rotated.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertEquals(401, send(root, "clusters", "GET", cookie, null).statusCode());
            assertEquals(200, send(root, "clusters", "GET", newCookie, null).statusCode());

            // 退出后复制旧 JWT 也无法访问，不能只依赖浏览器删除 Cookie。
            var logout = send(root, "auth/logout", "POST", newCookie, Map.of());
            assertEquals(200, logout.statusCode());
            assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
            assertEquals(401, send(root, "clusters", "GET", newCookie, null).statusCode());
            assertEquals(200, send(root, "clusters", "GET", otherCookie, null).statusCode());
        }
    }

    @Test
    public void sharedRegistryConnectionUsesCurrentBrowserSession() throws Exception {
        // 两个浏览器共用控制台中的一个存储连接，各自使用独立登录会话。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        String configuration = console(this.temporary);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration);
        try (BootApplication server = boot.start()) {
            Api first = new Api(server);
            first.login();
            String id = first.ok("clusters", "POST", profile("共享连接", url)).get("id").asText();
            String metadata = "metadata?mode=registry&clusterId=" + id + "&scope=cluster";
            first.ok(metadata, "PUT", Map.of("values", Map.of("owner", "first")));
            Api second = new Api(server);
            second.login();

            // 退出第一个浏览器，不得把它的 Token 缓存在注册中心连接中。
            first.ok("auth/logout", "POST", Map.of());
            assertEquals(401, first.send(metadata, "GET", null).statusCode());
            assertEquals("first", second.get(metadata).get("owner").asText());
            second.ok(metadata, "PUT", Map.of("values", Map.of("owner", "second")));
            assertEquals("second", second.get(metadata).get("owner").asText());
        }
    }

    @Test
    public void registryManagementRequiresAuthenticatedRequestContext() throws Exception {
        // 存储可访问，但脱离请求调用业务服务时没有当前登录凭据。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        String configuration = console(this.temporary);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration);
        try (BootApplication server = boot.start()) {
            ClusterProfile connection = JsonUtils.convertValue(profile("本地管理", url), ClusterProfile.class);
            ClusterService clusters = server.getAppContext().getInstance(ClusterService.class);

            // 不能通过内部固定 Token 绕过当前请求认证。
            ConsoleOperationException failure = assertThrows(ConsoleOperationException.class, () -> {
                clusters.test(connection);
            });
            assertInstanceOf(AuthenticationException.class, ExceptionUtils.getRootCause(failure));
        }
    }

    @Test
    public void disabledWebSecurityDoesNotDisableTargetAuthentication() throws Exception {
        // 控制台允许免登录，但目标实例的管理 RPC 仍要求独立凭据。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false).setManagementToken("target-secret");
        var configuration = application(registry, "secured-target", "default", true);
        String console = console(this.temporary, Map.of("security.enable", "false"));
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console);
        try (MetContext target = configuration.buildContext(); //
             BootApplication server = boot.start()) {
            target.start();
            Api api = new Api(server);
            String path = "instance?mode=direct&target=" + encode(target.getListenAddress("rsf").toString());

            // 缺失或错误凭据均被拒绝，填写目标凭据后才可读取实例。
            assertEquals(403, api.send(path, "GET", null).statusCode());
            api.managementToken("wrong");
            assertEquals(403, api.send(path, "GET", null).statusCode());
            api.managementToken("target-secret");
            assertEquals(target.getInstanceID(), api.get(path).at("/instance/instanceId").asText());
        }
    }

    @Test
    public void targetAuthenticationFailureKeepsConsoleSession() throws Exception {
        // 控制台登录与目标实例认证相互独立。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false).setManagementToken("target-secret");
        var configuration = application(registry, "secured-target", "default", true);
        String console = console(this.temporary);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(console);
        try (MetContext target = configuration.buildContext(); //
             BootApplication server = boot.start()) {
            target.start();
            Api api = new Api(server);
            api.login();
            api.managementToken("wrong");
            String path = "instance?mode=direct&target=" + encode(target.getListenAddress("rsf").toString());

            // 远端返回的 401 转为访问拒绝，不应退出控制台登录。
            assertEquals(403, api.send(path, "GET", null).statusCode());
            assertEquals("admin", api.get("auth/session").get("userName").asText());
        }
    }

    @Test
    public void disabledSecurityAllowsReadingAndWritingWithoutCookies() throws Exception {
        Path rootDirectory = this.temporary;
        Path configuration = rootDirectory.resolve("meteor.properties");
        String content = "hasor.boot.web.connectors.http.port=0\n" + //
                "data.directory=" + rootDirectory.toAbsolutePath().normalize() + "\n" + //
                "security.enable=false\n";
        Files.writeString(configuration, content, StandardCharsets.UTF_8);
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration.toUri().toString());
        try (BootApplication server = boot.start()) {
            Api api = new Api(server);

            // 无账号、无 Cookie，直接获得页面所需信息并操作集群连接。
            var session = api.get("auth/session");
            assertFalse(session.get("securityEnabled").asBoolean());
            assertEquals("", session.get("userName").asText());
            assertEquals(0, api.get("clusters").size());
            var cluster = api.ok("clusters", "POST", profile("无需登录", url));
            assertEquals(1, api.get("clusters").size());
            api.ok("clusters?id=" + cluster.get("id").asText(), "DELETE", null);
            assertEquals(0, api.get("clusters").size());

            // 旧浏览器 Cookie 被忽略；认证接口也不再要求凭据。
            URI root = URI.create("http://127.0.0.1:" + port(server) + "/api/");
            assertEquals(200, send(root, "clusters", "GET", "meteor_session=expired", null).statusCode());
            var login = api.send("auth/login", "POST", Map.of());
            assertEquals(200, login.statusCode());
            assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
            api.ok("auth/logout", "POST", Map.of());
            assertEquals(0, api.get("clusters").size());

            // 免登录不改变同源约束，跨站写入仍被拒绝。
            HttpRequest.Builder builder = HttpRequest.newBuilder(root.resolve("clusters"))//
                    .header("Origin", "https://foreign.invalid")//
                    .header("Content-Type", "application/json");
            var crossSite = builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var denied = HttpClient.newHttpClient().send(crossSite, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode());
        }
    }

    @Test
    public void restartInvalidatesPreviousJwt() throws Exception {
        String configuration = console(this.temporary);
        String cookie;
        Boot initialBoot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration);
        try (BootApplication server = initialBoot.start()) {
            URI root = URI.create("http://127.0.0.1:" + port(server) + "/api/");
            var credentials = Map.of("username", "admin", "password", "test-password");
            var login = send(root, "auth/login", "POST", "", credentials);
            cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        }

        // 不持久化登录密钥；同一部署重启后，旧令牌需要重新登录。
        Boot restartedBoot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration);
        try (BootApplication server = restartedBoot.start()) {
            URI root = URI.create("http://127.0.0.1:" + port(server) + "/api/");
            assertEquals(401, send(root, "auth/session", "GET", cookie, null).statusCode());
        }
    }

    private static HttpResponse<String> send(URI root, String path, String method, String cookie, Object body) //
            throws Exception {
        String content = body == null ? "" : JsonUtils.writeValueAsString(body);
        var request = HttpRequest.newBuilder(root.resolve(path)).header("Content-Type", "application/json");
        if (!cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        request.method(method, HttpRequest.BodyPublishers.ofString(content));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
