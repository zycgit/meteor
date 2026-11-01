/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.echo.TextEchoService;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 JVM 客户端与社区 Java 服务端分别通过真实网络和 Meteor 完成基础互通。 */
@NxnRpc(name = "connect", version = "1")
public class ConnectJavaInteropTest {
    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsCommunityJavaSdkServer() throws Exception {
        // 社区 SDK 的 Protobuf 4 图仅属于对端，宿主与产品仍使用既有 Protobuf 3。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        configuration.connector("http").option("httpVersion", 1) //
                .protocol("connect").contextPath("/").option("streamBody", true);
        try (ConnectJavaProcess peer = ConnectJavaProcess.startServer(); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();

            // 消费者不发布本地实现；SDK 前缀与数值递增均必须来自真实 HTTP 服务。
            InterAddress target = new InterAddress("connect", peer.host(), port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(service);
            ConnectMessage result = remote.echo(ConnectMessage.of("Meteor 中文 🙂", 6));
            assertEquals("connect-sdk:Meteor 中文 🙂", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaClientCallsMeteorProtobufService() throws Exception {
        // Meteor 仍发布普通 Java 接口，SDK 与服务端只共享标准 proto 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        var resource = new GrpcProtoResource(getClass().getResource("/protocol/proto/java-sdk-echo.proto"));
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            TextEchoService implementation = text -> {
                calls.incrementAndGet();
                return text;
            };
            server.publisher().rsfService(TextEchoService.class, implementation) //
                    .aliasName("connect", "meteor.java.sdk.TextEchoService").serialize("Protobuf") //
                    .bindResource("connect", resource).register();

            // 官方 JVM 对端由 Java main 直接使用 SDK，未经过 Node 或 Meteor 客户端。
            int port = server.getListenAddress("connect").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            try (ConnectJavaProcess peer = ConnectJavaProcess.start("CONNECT", target, //
                    "meteor.java.sdk.TextEchoService/echo", "Java Connect 中文 🙂")) {
                assertEquals("Java Connect 中文 🙂", peer.awaitResult());
                assertEquals(1, calls.get());
            }
        }
    }
}
