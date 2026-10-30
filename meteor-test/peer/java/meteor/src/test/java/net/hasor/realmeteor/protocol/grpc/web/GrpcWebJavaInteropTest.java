/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.protocol.connect.ConnectJavaProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 官方 Connect JVM SDK 的 gRPC-Web 模式，由 Java 源码调用同协议提供者。 */
public class GrpcWebJavaInteropTest {
    @Test(timeout = 30000)
    public void officialJavaClientCallsMeteorGrpcWebService() throws Exception {
        // 只挂载 grpc-web，不开放普通 grpc 端点，避免误测另一种线上协议。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("grpc-web").contextPath("/web").option("streamBody", true);
        var resource = new GrpcProtoResource(getClass().getResource("/protocol/proto/java-sdk-echo.proto"));
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            TextEchoService implementation = text -> {
                calls.incrementAndGet();
                return text;
            };
            server.publisher().rsfService(TextEchoService.class, implementation) //
                    .aliasName("grpc-web", "meteor.java.sdk.TextEchoService").serialize("Protobuf") //
                    .bindResource("grpc-web", resource).register();

            // 显式选择 SDK 的 GRPC_WEB，不借用普通 gRPC-Java 调用冒充覆盖。
            int port = server.getListenAddress("grpc-web").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/web";
            try (ConnectJavaProcess peer = ConnectJavaProcess.start("GRPC_WEB", target, //
                    "meteor.java.sdk.TextEchoService/echo", "Java gRPC-Web 中文 🙂")) {
                assertEquals("Java gRPC-Web 中文 🙂", peer.awaitResult());
                assertEquals(1, calls.get());
            }
        }
    }
}
