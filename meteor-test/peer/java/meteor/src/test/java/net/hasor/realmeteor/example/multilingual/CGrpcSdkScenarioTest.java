/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** gRPC Core 的公开 C API 与 Meteor 直接进行单次 unary，不使用 C++ 包装或代理。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/c/grpc/gRPC-Core@{0}/{1}", quoteTextArguments = false)
@MethodSource("versions")
@NxnRpc(name = "grpc")
public class CGrpcSdkScenarioTest {
    private final String executable;

    public CGrpcSdkScenarioTest(String version, String profile, String executable) {
        this.executable = executable;
    }
    public static List<Object[]> versions() {
        // 发行包基线与上游最新分别使用自己的二进制，不能共用已解析的运行库。
        List<Object[]> versions = new ArrayList<>();
        versions.add(new Object[] { "1.51.1", "debian-baseline", "/opt/meteor-peer/native/c/grpc/echo-peer" });
        String latest = "/opt/meteor-peer/native/versions/grpc/1.85.0/c/echo-peer";
        versions.add(new Object[] { "1.85.0", "upstream-latest", latest });
        return versions;
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sdkToMeteorGrpcEcho() throws Exception {
        // 普通 Java 接口经 bootstrap 发布，共用测试契约中的标准 StringValue 方法。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("0.0.0.0", 0).option("httpVersion", 2) //
                .protocol("grpc").contextPath("/");
        URL contract = this.getClass().getResource("/protocol/proto/java-sdk-echo.proto");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .protocol("grpc").serialize("Protobuf") //
                    .aliasName("grpc", "meteor.java.sdk.TextEchoService") //
                    .bindResource("grpc", new GrpcProtoResource(contract)).register();
            int port = provider.getListenAddress("grpc").getPort();
            String target = "grpc://" + ContainerProcess.runnerHost() + ":" + port;

            // 独立 C 进程以 Core channel 和 batch API 调用一次，校验业务字符串后正常退出。
            String message = "C gRPC Core → Meteor 中文";
            List<String> command = List.of(this.executable, "consumer", message);
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("RESULT " + message), output);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorToSdkGrpcEcho() throws Exception {
        // Core C server 独占动态端口；Meteor 只创建出站 HTTP/2 通道。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").option("httpVersion", 2).protocol("grpc").contextPath("/");
        URL contract = this.getClass().getResource("/protocol/proto/java-sdk-echo.proto");
        List<String> command = List.of(this.executable, "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("READY "));
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .protocol("grpc").serialize("Protobuf") //
                    .aliasName("grpc", "meteor.java.sdk.TextEchoService") //
                    .bindResource("grpc", new GrpcProtoResource(contract)).register();

            // 生成的 Protobuf-C codec 处理消息，Core 负责 HTTP/2 和 gRPC 状态。
            InterAddress address = new InterAddress("grpc", target.getHost(), target.getPort(), "default");
            TextEchoService remote = consumer.getRsfClient(address).getRemote(service);
            String message = "Meteor → C gRPC Core 中文";
            assertEquals(message, remote.echo(message));
        }
    }
}
