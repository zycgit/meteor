/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.contract.scalar.ScalarValueServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 标准 JSON-RPC 报文的字段缺失探针，直接观察业务入口收到的默认值。 */
@NxnRpc(name = "jsonrpc", version = "2.0")
public class ScalarMissingMemberTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    public Path data;

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingNullableNoteReachesBusinessAsNull() throws Exception {
        // 独立启动提供者，并在共享业务实现处理前观察实际解码的 DTO。
        MetConfiguration configuration = ScalarValueProfile.JSONRPC.providerConfiguration();
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        AtomicReference<ScalarValue> observed = new AtomicReference<>();
        ScalarValueServiceImpl implementation = new ScalarValueServiceImpl() {
            @Override
            public ScalarValue echo(ScalarValue request) {
                observed.set(request);
                return super.echo(request);
            }
        };
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            ScalarValueProfile.JSONRPC.publish(provider, implementation);

            // 外部 HTTP 客户端发送真正缺少 note 属性的标准 JSON-RPC 对象参数。
            int port = provider.getListenAddress("jsonrpc").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/scalar");
            String body = """
                    {"jsonrpc":"2.0","method":"scalarValues.echo","params":[{
                      "flag":true,"integer":42,"single":1.5,"decimal":2.25,
                      "kind":"WHOLESALE","receipt":"client"
                    }],"id":1}
                    """;
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 同时核对业务入口的 null 和返回收据，防止只检查响应默认值。
            assertEquals(200, response.statusCode());
            ScalarValue received = observed.get();
            assertNotNull(received);
            assertNull(received.getNote());
            assertTrue(received.isFlag());
            assertEquals(42L, received.getInteger());
            assertEquals("client", received.getReceipt());
            JsonNode envelope = JSON.readTree(response.body());
            assertEquals("2.0", envelope.path("jsonrpc").asText());
            assertFalse(envelope.has("error"));
            assertEquals(1, envelope.path("id").asInt());
            JsonNode result = envelope.path("result");
            assertTrue(result.isObject());
            JsonNode note = result.path("note");
            assertTrue(note.isMissingNode() || note.isNull());
            assertTrue(result.path("flag").asBoolean());
            assertEquals("accepted:WHOLESALE", result.path("receipt").asText());
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingPrimitiveFlagReachesBusinessAsFalse() throws Exception {
        // 独立启动提供者，并在共享业务实现处理前观察实际解码的 DTO。
        MetConfiguration configuration = ScalarValueProfile.JSONRPC.providerConfiguration();
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        AtomicReference<ScalarValue> observed = new AtomicReference<>();
        ScalarValueServiceImpl implementation = new ScalarValueServiceImpl() {
            @Override
            public ScalarValue echo(ScalarValue request) {
                observed.set(request);
                return super.echo(request);
            }
        };
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            ScalarValueProfile.JSONRPC.publish(provider, implementation);

            // 外部 HTTP 客户端发送真正缺少 flag 属性、但仍包含 note 的对象参数。
            int port = provider.getListenAddress("jsonrpc").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/scalar");
            String body = """
                    {"jsonrpc":"2.0","method":"scalarValues.echo","params":[{
                      "integer":42,"single":1.5,"decimal":2.25,
                      "kind":"WHOLESALE","note":"present-note","receipt":"client"
                    }],"id":2}
                    """;
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 业务入口收到 primitive 默认值 false，显式 note 与成功收据保持完整。
            assertEquals(200, response.statusCode());
            ScalarValue received = observed.get();
            assertNotNull(received);
            assertFalse(received.isFlag());
            assertEquals("present-note", received.getNote());
            assertEquals(42L, received.getInteger());
            assertEquals("client", received.getReceipt());
            JsonNode envelope = JSON.readTree(response.body());
            assertEquals("2.0", envelope.path("jsonrpc").asText());
            assertFalse(envelope.has("error"));
            assertEquals(2, envelope.path("id").asInt());
            JsonNode result = envelope.path("result");
            assertTrue(result.isObject());
            assertTrue(result.path("flag").isBoolean());
            assertFalse(result.path("flag").asBoolean());
            assertEquals("present-note", result.path("note").asText());
            assertEquals("accepted:WHOLESALE", result.path("receipt").asText());
        }
    }
}
