/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.connect.python;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 官方 Connect Python SDK 的基础订单互通，不以 gRPC 或代理链路替代目标协议。 */
@RunWith(Parameterized.class)
public class ConnectPythonSdkVersionInteropTest {
    private final String version;
    private final String protocol;
    private final String messageApi;
    private final String direction;

    public ConnectPythonSdkVersionInteropTest(String version, String protocol, String messageApi, String direction) {
        this.version = version;
        this.protocol = protocol;
        this.messageApi = messageApi;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/python/{1}/connectrpc@{0}/{2}/{3}")
    public static Object[][] protocols() {
        // 0.11 切换默认 Protobuf 实现；分别保留 Google 兼容入口和新的原生生成入口。
        List<Object[]> parameters = new ArrayList<>();
        for (String version : List.of("0.10.1", "0.11.1", "0.13.0")) {
            List<String> messageApis = new ArrayList<>();
            messageApis.add("google");
            if (!StringUtils.equals(version, "0.10.1")) {
                messageApis.add("native");
            }
            for (String protocol : List.of("connect", "grpc-web")) {
                for (String messageApi : messageApis) {
                    parameters.add(new Object[] { version, protocol, messageApi, "SDK-to-Meteor" });
                    parameters.add(new Object[] { version, protocol, messageApi, "Meteor-to-SDK" });
                }
            }
        }
        return parameters.toArray(new Object[0][]);
    }

    @Test(timeout = 60000)
    public void ordersRoundTripWithTheSelectedSdkVersion() throws Exception {
        // 每个参数实例只执行一个调用方向，原始 SDK 版本由 Python 进程再次核验并写入日志。
        if (StringUtils.equals(this.direction, "SDK-to-Meteor")) {
            this.pythonSdkConsumerSavesAndFindsMeteorOrder();
        } else {
            this.meteorConsumerSavesAndFindsPythonSdkOrder();
        }
    }

    private void pythonSdkConsumerSavesAndFindsMeteorOrder() throws Exception {
        // 从公开入口挂载当前协议，使用测试工程独立持有的订单契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        var connector = configuration.connector("http").bind(ContainerProcess.runnerHost(), 0);
        connector.protocol(this.protocol).contextPath("/");
        var resource = new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol(this.protocol).aliasName(this.protocol, "meteor.example.OrderDemoService") //
                    .serialize("Protobuf").bindResource(this.protocol, resource).register();

            // 官方 SDK 在独立 Python 容器中保存并查询同一笔订单，实际使用所选线协议。
            String target = "http://" + provider.getListenAddress(this.protocol).getHostPort();
            try (PythonConnectPeerProcess peer = PythonConnectPeerProcess.consumer( //
                    this.version, this.protocol, this.messageApi, target)) {
                JsonNode result = peer.awaitResult();
                assertEquals(1001, result.required("id").intValue());
                assertEquals("Python Connect 订单 🙂", result.required("description").textValue());
                assertEquals("TWV0ZW9y", result.required("attachment").textValue());
            }
        }
    }

    private void meteorConsumerSavesAndFindsPythonSdkOrder() throws Exception {
        // Python 官方 WSGI 服务自行监听端口，Meteor 仅配置当前协议的出站能力。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.connector("http").protocol(this.protocol).contextPath("/");
        var resource = new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
        try (PythonConnectPeerProcess peer = PythonConnectPeerProcess.provider( //
                this.version, this.protocol, this.messageApi); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            var service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol(this.protocol).aliasName(this.protocol, "meteor.example.OrderDemoService") //
                    .serialize("Protobuf").bindResource(this.protocol, resource).register();
            // 地址前缀沿用当前协议的配置；底层 HTTP 传输不改变协议选择。
            var address = new InterAddress(this.protocol, peer.host(), port, "default");
            OrderDemoService remote = consumer.getRsfClient(address).getRemote(service);

            // 两次真实网络 RPC 验证对端保存状态，以及普通对象、Unicode 和二进制附件。
            Order request = new Order();
            request.setId(1001);
            request.setDescription("Meteor Python 订单 🙂");
            request.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            Order saved = remote.save(request);
            OrderQuery query = new OrderQuery();
            query.setId(request.getId());
            Order found = remote.find(query);
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }
}
