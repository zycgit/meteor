/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.scenario;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.boot.BootApplication;
import net.hasor.boot.web.WebServer;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 测试只通过正式的配置、扩展和 HTTP 入口创建应用与控制台。 */
final class ConsoleTestSupport {
    static JdbcRegistryConfiguration registry(String url) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        source.setPassword("");
        var resource = ConsoleTestSupport.class.getResourceAsStream("/META-INF/meteor/schema/h2.sql");
        try (var connection = source.getConnection(); //
             var script = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
        }
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(source);
        registry.setManagementToken("test-token");
        registry.setRefreshMillis(100);
        return registry;
    }

    static MetConfiguration application(RegistryConfiguration registry, String name, String unit, boolean bind) {
        MetConfiguration config = new MetConfiguration();
        config.setApplicationName(name);
        config.setUnitName(unit);
        config.setDefaultTimeout(2000);
        config.setConnectTimeout(1000);
        config.addExtension(registry);
        var tcp = config.connector("tcp");
        if (bind) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("rsf");
        return config;
    }

    static String console(Path directory) throws IOException {
        return console(directory, Map.of());
    }

    static String console(Path directory, Map<String, String> overrides) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("hasor.boot.web.connectors.http.host", "127.0.0.1");
        properties.put("hasor.boot.web.connectors.http.port", "0");
        properties.put("hasor.boot.web.health.enabled", "false");
        properties.put("data.directory", directory.toAbsolutePath().normalize().toString());
        properties.put("meteor.unit", "default");
        properties.put("session.secureCookie", "false");
        properties.put("security.enable", "true");
        properties.put("security.account", "admin:test-password");
        properties.putAll(overrides);

        StringBuilder content = new StringBuilder();
        properties.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        Path configuration = directory.resolve("meteor.properties");
        Files.createDirectories(directory);
        Files.writeString(configuration, content, StandardCharsets.UTF_8);
        return configuration.toUri().toString();
    }

    static Map<String, Object> profile(String name, String url) {
        return Map.of(//
                "name", name, //
                "type", "jdbc", //
                "database", "h2", //
                "address", url, //
                "username", "sa", //
                "password", "", //
                "managementToken", "test-token");
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    static int port(BootApplication application) {
        return application.getAppContext().getInstance(WebServer.class).getPort();
    }

    static final class Api {
        private String managementToken;

        void managementToken(String token) {
            this.managementToken = token;
        }

        private final HttpClient http;
        private final URI        root;

        Api(BootApplication application) {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
            this.root = URI.create("http://127.0.0.1:" + port(application));
        }

        void login() throws Exception {
            this.ok("auth/login", "POST", Map.of("username", "admin", "password", "test-password"));
        }

        JsonNode get(String path) throws Exception {
            return this.ok(path, "GET", null);
        }

        JsonNode ok(String path, String method, Object body) throws Exception {
            HttpResponse<String> response = this.send(path, method, body);
            assertEquals(200, response.statusCode(), response.body());
            String contentType = response.headers().firstValue("Content-Type").orElseThrow();
            assertEquals("application/json", contentType.split(";", 2)[0]);
            return JsonUtils.readTree(response.body());
        }

        HttpResponse<String> send(String path, String method, Object body) throws Exception {
            String value = body == null ? "" : JsonUtils.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(this.root.resolve("/api/" + path))//
                    .timeout(Duration.ofSeconds(15))//
                    .header("Content-Type", "application/json");
            if (path.contains("mode=direct") && this.managementToken != null) {
                String token = Base64.getEncoder().encodeToString(this.managementToken.getBytes(StandardCharsets.UTF_8));
                builder.header("X-Meteor-Management-Token", token);
            }
            HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(value)).build();
            return this.http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
