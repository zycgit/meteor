/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** 真实浏览器仅作为 gRPC-Web 消费者，官方传输与 Buf 原生 BigInt 编码共同验证类型精度。 */
@RunWith(Parameterized.class)
public class BrowserGrpcWebTypeScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final        String       format;
    private final        String       vector;

    public BrowserGrpcWebTypeScenarioTest(String format, String vector, String type) {
        this.format = format;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/browser/grpcweb-{0}/direct/{2}/{1}/meteor-provider")
    public static List<Object[]> scenarios() throws Exception {
        // 二进制和 Base64 文本是 gRPC-Web 传输形态；消息自身均使用同一份 Protobuf 类型契约。
        List<Object[]> scenarios = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            String type = TypeValueVectors.type(vector);
            scenarios.add(new Object[] { "binary", vector, type });
            scenarios.add(new Object[] { "text", vector, type });
        }
        return scenarios;
    }

    @Test(timeout = 45000)
    public void officialBrowserPreservesDeclaredType() throws Exception {
        // 浏览器先建立独立页面 origin，公开配置只允许该来源，不绕过 CORS 或伪造浏览器服务端。
        try (TypeNodePeerProcess peer = TypeNodePeerProcess.browser()) {
            String origin = peer.awaitReady().required("origin").textValue();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
            configuration.connector("http").bind(ContainerProcess.runnerHost(), 0).option("httpVersion", 1) //
                    .protocol("grpc-web").contextPath("/types").option("corsOrigins", origin);
            GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/vectors/types/types.proto"));
            try (MetContext server = configuration.buildContext()) {
                // 服务容器启动后正常发布接口和协议资源，所有业务流量经过 Caller/Dispatcher 链路。
                server.start();
                server.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                        .aliasName("grpc-web", "meteor.types.TypeEchoService").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource("grpc-web", resource).register();

                // 当前实例只发送一个向量，BigInt、字节数组和可空值均在 Chromium 内构造与读取。
                int port = server.getListenAddress("grpc-web").getPort();
                String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/types";
                Map<String, String> command = Map.of("target", target, "format", this.format, "vector", this.vector);
                JsonNode result = peer.invoke(command);
                TypeValue actual = JSON.treeToValue(result.required("value"), TypeValue.class);
                TypeValueAssertions.assertVector(this.vector, TypeValueVectors.value(this.vector), actual);
            }
        }
    }
}
