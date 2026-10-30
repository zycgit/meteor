/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Loams 0.1.3 源码 SDK 的客户端；本类记录通用 Unary API 的单向基础证据。 */
public class ConnectLoamsCppInteropTest {

    @Test(timeout = 60000)
    public void loamsCppConnectClientCallsMeteorProvider() throws Exception {
        // 从 bootstrap 启动 Connect 提供者，沿用已有文本回声契约和普通 Java 接口。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        var resource = new GrpcProtoResource(getClass().getResource("/protocol/proto/java-sdk-echo.proto"));
        AtomicInteger calls = new AtomicInteger();
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            TextEchoService implementation = text -> {
                calls.incrementAndGet();
                return text + "|meteor";
            };
            provider.publisher().rsfService(TextEchoService.class, implementation) //
                    .aliasName("connect", "meteor.java.sdk.TextEchoService").serialize("Protobuf") //
                    .bindResource("connect", resource).register();

            // 独立 C++ 进程直接使用未修改 SDK 的 Unary、默认 HTTP Transport 与 Protobuf。
            int port = provider.getListenAddress("connect").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port;
            String message = "Loams Connect → Meteor 中文 <&>";
            String executable = "/opt/meteor-peer/native/connect/cpp/echo-peer";
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", List.of(executable, message), target)) {
                String output = consumer.awaitOutput();
                assertTrue(output, output.contains("OK Loams 0.1.3: " + message + "|meteor"));
                assertEquals(1, calls.get());
            }
        }
    }
}
