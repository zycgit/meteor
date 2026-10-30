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
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** 每个实例只验证一种编码、方向和类型向量；官方 JSON SDK 的负零限制单独记录。 */
@RunWith(Parameterized.class)
public class NodeConnectTypeScenarioTest {
    private static final ObjectMapper JSON    = new ObjectMapper();
    private static final String       SERVICE = "meteor.types.TypeEchoService";
    private final        String       direction;
    private final        String       serialization;
    private final        String       format;
    private final        String       vector;

    public NodeConnectTypeScenarioTest(String direction, String serialization, String format, String vector, String type) {
        this.direction = direction;
        this.serialization = serialization;
        this.format = format;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/node/connect-{1}/direct/{4}/{3}/meteor-{0}")
    public static List<Object[]> scenarios() throws Exception {
        // 参数顺序固定为 Meteor 角色、RPC 编码、当前类型向量，逐项形成独立 JUnit 实例。
        List<Object[]> scenarios = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            String type = TypeValueVectors.type(vector);
            scenarios.add(new Object[] { "provider", "Protobuf", "proto", vector, type });
            scenarios.add(new Object[] { "consumer", "Protobuf", "proto", vector, type });
            scenarios.add(new Object[] { "provider", "Json", "json", vector, type });
            scenarios.add(new Object[] { "consumer", "Json", "json", vector, type });
        }
        return scenarios;
    }

    @Test(timeout = 45000)
    public void preservesDeclaredTypeAcrossOfficialNodeSdk() throws Exception {
        if (StringUtils.equals(this.direction, "provider")) {
            this.meteorProvides();
        } else {
            this.meteorConsumes();
        }
    }

    private void meteorProvides() throws Exception {
        // 通过 bootstrap 启动仅监听 HTTP/1 的 Connect 端点，再发布真实 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/protocol/proto/types/types.proto"));
        try (MetContext server = configuration.buildContext(); //
             TypeNodePeerProcess peer = TypeNodePeerProcess.connect("consumer", this.format)) {
            peer.awaitReady();
            server.start();
            server.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource("connect", resource).register();

            // 官方 SDK 在自己的容器中构造原生值并直接调用 Meteor，控制通道只返回观察结果。
            int port = server.getListenAddress("connect").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/types";
            JsonNode result = peer.invoke(Map.of("target", target, "vector", this.vector));
            TypeValue actual = JSON.treeToValue(result.required("value"), TypeValue.class);
            this.assertVector(TypeValueVectors.value(this.vector), actual);
        }
    }

    private void meteorConsumes() throws Exception {
        // 独立官方 Node 提供者拥有监听；Meteor 只启用出站通道并订阅普通 Java 接口。
        MetConfiguration configuration = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/protocol/proto/types/types.proto"));
        try (TypeNodePeerProcess peer = TypeNodePeerProcess.connect("provider", this.format); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitReady().required("port").intValue();
            client.start();
            MetBindInfo<TypeEchoService> info = client.publisher().rsfService(TypeEchoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress("connect", peer.host(), port, "default");
            TypeEchoService remote = client.getRsfClient(target).getRemote(info);

            // 每次只断言当前向量的字段和服务端收据，整数、浮点位模式不经过字符串 RPC 代替。
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(expected);
            this.assertVector(expected, actual);
        }
    }

    private void assertVector(TypeValue expected, TypeValue actual) throws Exception {
        // Buf JSON 输出经 JSON.stringify 将负零变为正零；此处只验证这两个明确的 SDK 限制。
        boolean json = StringUtils.equals(this.format, "json");
        boolean singleNegativeZero = StringUtils.equals(this.vector, "float32-negative-zero");
        boolean doubleNegativeZero = StringUtils.equals(this.vector, "float64-negative-zero");
        if (json && (singleNegativeZero || doubleNegativeZero)) {
            assertNotNull(actual);
            assertEquals(this.vector, actual.getCaseId());
            assertEquals("accepted:" + this.vector, actual.getReceipt());
            if (singleNegativeZero) {
                assertEquals(0, Float.floatToRawIntBits(actual.getFloat32Value()));
            } else {
                assertEquals(0L, Double.doubleToRawLongBits(actual.getFloat64Value()));
            }
            return;
        }

        // 其它类型、Protobuf 负零及整数边界仍使用统一的严格字段和原始位模式断言。
        TypeValueAssertions.assertVector(this.vector, expected, actual);
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        var connector = configuration.connector("http").option("httpVersion", 1);
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("connect").contextPath("/types");
        return configuration;
    }
}
