/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.direct.ProtocolDescriptionExample;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.builtin.GrpcHealthService;
import net.hasor.meteor.protocol.grpc.builtin.GrpcReflectionService;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 协议自带的描述和标准服务，复用订单示例的同一个监听端点。 */
public class ProtocolDescriptionScenarioTest {
    @Test(timeout = 30000)
    public void soapDirectoryLinksToTheGeneratedOrderWsdl() throws Exception {
        // 从公开 bootstrap 启动例子，只发布一份订单业务实现。
        MetConfiguration configuration = DirectRpcExample.server("127.0.0.1", 0, 0);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());

            // SOAP 目录与业务请求共用 HTTP 端口，目录中的链接指向生成的 WSDL。
            int port = server.getListenAddress("soap").getPort();
            HttpResponse<String> directory = this.get(port, "/soap");
            assertEquals(200, directory.statusCode());
            assertTrue(directory.body(), directory.body().contains("orders"));
            assertTrue(directory.body(), directory.body().contains("/soap/orders?wsdl"));

            // 描述来自真实接口，包含订单服务的保存和查询操作。
            HttpResponse<String> wsdl = this.get(port, "/soap/orders?wsdl");
            assertEquals(200, wsdl.statusCode());
            assertTrue(wsdl.body(), wsdl.body().contains("name=\"save\""));
            assertTrue(wsdl.body(), wsdl.body().contains("name=\"find\""));
        }
    }

    @Test(timeout = 30000)
    public void grpcHealthReportsThePublishedOrderServiceAsServing() throws Exception {
        // 服务端显式安装标准服务，客户端只配置 gRPC 出口。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());

            // 标准契约使用完整根路径，业务服务名称取自相同的发布声明。
            MetBindInfo<GrpcHealthService> binding = ProtocolDescriptionExample.subscribeHealth(client);
            GrpcHealthService health = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);
            JsonNode request = new ObjectMapper().createObjectNode().put("service", DirectRpcExample.SERVICE_NAME);
            JsonNode response = health.Check(request);
            assertEquals("SERVING", response.path("status").asText());
        }
    }

    @Test(timeout = 30000)
    public void grpcReflectionListsThePublishedOrderService() throws Exception {
        // 同一个 gRPC 监听同时提供业务 context 和标准 Reflection 根路径。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());

            // 通过公开 Java 接口发起标准双向流，用户无需处理 HTTP/2 帧或 Protobuf 字节。
            MetBindInfo<GrpcReflectionService> binding = ProtocolDescriptionExample.subscribeReflection(client);
            GrpcReflectionService reflection = client.getRsfClient(server.getListenAddress("grpc")) //
                    .getRemote(binding);
            JsonNode request = new ObjectMapper().createObjectNode().put("listServices", "");
            var requests = MetCaller.fromIterable(List.of(request));
            List<JsonNode> replies = new ArrayList<>();
            MetCaller.consume(reflection.ServerReflectionInfo(requests), replies::add).get(5, TimeUnit.SECONDS);

            // 标准服务目录列出实际已发布的业务别名。
            assertEquals(1, replies.size());
            boolean found = false;
            for (JsonNode service : replies.get(0).path("listServicesResponse").path("service")) {
                if (StringUtils.equals(DirectRpcExample.SERVICE_NAME, service.path("name").asText())) {
                    found = true;
                    break;
                }
            }
            assertTrue(replies.toString(), found);
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        URI address = URI.create("http://127.0.0.1:" + port + path);
        HttpRequest request = HttpRequest.newBuilder(address).GET().build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)) //
                .version(HttpClient.Version.HTTP_1_1).build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return response.get(5, TimeUnit.SECONDS);
        } finally {
            response.cancel(true);
        }
    }
}
