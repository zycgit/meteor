/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.echo.TextEchoService;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 官方 JVM 客户端与社区 Java 服务端分别通过真实网络和 Meteor 完成基础互通。 */
public class ConnectJavaInteropTest {
    @Test(timeout = 45000)
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
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(service);
            ConnectMessage result = remote.echo(ConnectMessage.of("Meteor 中文 🙂", 6));
            assertEquals("connect-sdk:Meteor 中文 🙂", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 30000)
    public void officialJavaClientCallsMeteorProtobufService() throws Exception {
        // Meteor 仍发布普通 Java 接口，SDK 与服务端只共享标准 proto 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        var resource = new GrpcProtoResource(getClass().getResource("/contracts/java-sdk-echo.proto"));
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
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (ConnectJavaProcess peer = ConnectJavaProcess.start("CONNECT", target, //
                    "meteor.java.sdk.TextEchoService/echo", "Java Connect 中文 🙂")) {
                assertEquals("Java Connect 中文 🙂", peer.awaitResult());
                assertEquals(1, calls.get());
            }
        }
    }
}
