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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.contract.scalar.ScalarValueService;
import net.hasor.example.contract.scalar.ScalarValueServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Timeout;

/** 原始 ProtoJSON HTTP 请求必须保留尚未绑定到 double 的整数词法负零。 */
@NxnRpc(name = "triple")
public class ScalarTripleRawJsonTest {
    @TempDir
    public Path data;

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void integerNegativeZeroInAnHttpArgumentReachesBusinessAsNegativeDoubleZero() throws Exception {
        // 通过正式 bootstrap 发布带显式 schema 的原生 Triple JSON 服务。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setUnitName("scalar_raw_json");
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        configuration.connector("http").bind("127.0.0.1", 0).protocol("triple") //
                .contextPath("/scalar").option("httpMode", "json");
        GrpcProtoResource schema = new GrpcProtoResource(ScalarValueProfile.class //
                .getResource("/protocol/proto/scalar/values.proto"));
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
            provider.publisher().rsfService(ScalarValueService.class, implementation) //
                    .aliasName("triple", "meteor.scalar.ScalarValueService") //
                    .serialize("Json").bindResource("triple", schema).register();

            // 外部 HTTP 客户端发送数组参数中的原始 -0，不先构造 Java Double 或 JsonNode。
            int port = provider.getListenAddress("triple").getPort();
            String path = "/scalar/meteor.scalar.ScalarValueService/echo";
            URI endpoint = URI.create("http://127.0.0.1:" + port + path);
            String body = """
                    [{"flag":true,"integer":42,"single":1.5,"decimal":-0,
                      "kind":"WHOLESALE","note":"wire-note","receipt":"client"}]
                    """;
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .header("Content-Type", "application/json") //
                    .header("tri-protocol-version", "1") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            // 使用单调等待保留 5 秒预算，避免墙钟跳变提前触发 JDK 请求定时器。
            var exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                response = exchange.get(5, TimeUnit.SECONDS);
            } finally {
                exchange.cancel(true);
            }

            // 同时检查业务入口与实际响应，确保数组解包和原生绑定均未丢失符号位。
            assertEquals(200, response.statusCode());
            ScalarValue received = observed.get();
            assertNotNull(received);
            assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(received.getDecimal()));
            assertEquals(42L, received.getInteger());
            JsonNode result = new ObjectMapper().readTree(response.body());
            assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(result.path("decimal").doubleValue()));
            assertEquals("accepted:WHOLESALE", result.path("receipt").asText());
        }
    }
}
