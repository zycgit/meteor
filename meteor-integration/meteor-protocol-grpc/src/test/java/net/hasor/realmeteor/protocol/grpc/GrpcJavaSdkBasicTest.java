/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.peer.protocol.grpc.GrpcSdkContract;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 同一 JUnit 进程内，官方 grpc-java 与 Meteor 通过真实网络完成双向基础调用。 */
public class GrpcJavaSdkBasicTest {
    @Test(timeout = 30000)
    public void officialJavaSdkCallsPublishedProtobufService() throws Exception {
        // 只挂载当前被测协议，不能以其它协议成功代替本协议可用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("grpc").option("streamBody", true);
        GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/proto/echo.proto"));
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", resource).register();

            // SDK 的 Netty 通道和 ClientCalls 发起真正的 gRPC 请求，不使用 Meteor 消费者。
            int port = server.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                GrpcSdkContract contract = new GrpcSdkContract();
                String expected = "Java SDK grpc 中文 🙂";
                DynamicMessage request = contract.message(expected, 6);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                DynamicMessage result = ClientCalls.blockingUnaryCall(channel, contract.method(), options, request);
                assertEquals(contract.message(expected, 7), result);
                assertEquals(1, implementation.getCalls());
            } finally {
                channel.shutdownNow();
                assertTrue("gRPC 客户端通道未在 5 秒内关闭", channel.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorConsumerCallsExternalJavaSdkServer() throws Exception {
        // 当前方法拥有官方 SDK 服务端；只在 start 完成后创建 Meteor 消费者。
        GrpcSdkContract contract = new GrpcSdkContract();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(contract.service("grpc-sdk:")).build().start();
        try {
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
            configuration.connector("http").option("httpVersion", 2) //
                    .protocol("grpc").option("streamBody", true);
            GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/proto/echo.proto"));
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class) //
                        .aliasName("grpc", "example.Echo").serialize("Protobuf") //
                        .bindResource("grpc", resource).register();

                // SDK 前缀与数值变化来自网络服务，当前 Meteor 容器不发布任何本地实现。
                InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
                Echo remote = client.getRsfClient(address).getRemote(service);
                Message result = remote.echo(Message.of("反向 Java 🙂", 6));
                assertEquals("grpc-sdk:反向 Java 🙂", result.getValue());
                assertEquals(7, result.getCount());
            }
        } finally {
            server.shutdownNow();
            assertTrue("gRPC 服务端未在 5 秒内关闭", server.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
