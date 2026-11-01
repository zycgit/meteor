/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "grpc")
public class GrpcPublicationTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingDefaultProtoKeepsTheLocalServiceWithoutExposingGrpc() throws Exception {
        // 正式注册事件仍描述本地服务，缺少 proto 只让 gRPC 拒绝这个端点。
        MetConfiguration configuration = this.configuration();
        EchoImpl implementation = new EchoImpl();
        try (MetContext context = configuration.buildContext()) {
            AtomicInteger registered = new AtomicInteger();
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.REGISTERED) {
                    registered.incrementAndGet();
                }
            });
            MetBindInfo<Echo> info = context.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").protocol("grpc").register();

            // 服务和别名仍可通过公开查询取得，注册事件正常发布。
            MetBindInfo<?> published = context.getServiceInfo(Echo.class);
            assertNotNull(published);
            assertEquals(info.getBindID(), published.getBindID());
            assertTrue(context.getServiceIDs().contains(info.getBindID()));
            assertEquals(1, registered.get());
            context.start();

            // 官方客户端实际访问该方法必须收到 UNIMPLEMENTED，不能执行本地实现。
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                MethodDescriptor<Empty, Empty> method = this.echoMethod();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Empty request = Empty.getDefaultInstance();
                var failure = assertThrows(StatusRuntimeException.class, // 保留真实网络调用的拒绝结果。
                        () -> ClientCalls.blockingUnaryCall(peer, method, options, request));
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }

            // 网络拒绝不会撤销本地提供者，公开代理仍能直接调用它。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("local provider", 2));
            assertEquals("local provider", response.getValue());
            assertEquals(3, response.getCount());
            assertEquals(1, implementation.getCalls());
        }
    }

    @Test
    public void extensionPublicationKeepsTheLocalServiceWhenGrpcRejectsItsDefinition() throws Exception {
        // 扩展在构建期间发布普通 Java 服务，保留 gRPC 默认 Protobuf 配置。
        MetConfiguration configuration = this.configuration();
        configuration.addExtension(context -> {
            context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").register();
            return new MetExtension() {
                @Override
                public <T> T getService(Class<T> type) {
                    return null;
                }

                @Override
                public void beforeStart() {
                }

                @Override
                public void afterStart() {
                }

                @Override
                public void close() {
                }
            };
        });

        // 构建和启动均可完成，但该服务没有可用的 gRPC 端点。
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> info = context.getServiceInfo(Echo.class);
            assertNotNull(info);
            context.start();
            assertTrue(context.isOnline());

            // 通过公开客户端优先调用本地提供者，协议拒绝不会撤销本地能力。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("local remains available", 1));
            assertEquals("local remains available", response.getValue());
            assertEquals(2, response.getCount());
        }
    }

    @Test
    public void explicitProtobufAfterStartupKeepsTheLocalService() throws Exception {
        // 挂载默认 JSON，服务显式选择 Protobuf 并省略 proto 资源。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("grpc").contextPath("/rpc").option("serializeType", "Json");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            ConfigurationBuilder<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl());
            MetBindInfo<Echo> info = service.aliasName("grpc", "example.Echo").serialize("pRoToBuF") //
                    .contextPathByPrefix("grpc", "/rpc").register();

            // 显式序列化优先于挂载默认值，路径声明不会影响本地服务保留。
            MetBindInfo<?> published = context.getServiceInfo(Echo.class);
            assertNotNull(published);
            assertEquals(info.getBindID(), published.getBindID());
            assertTrue(context.isOnline());

            // 通过公开客户端优先调用本地提供者，协议拒绝不会撤销本地能力。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("local remains available", 1));
            assertEquals("local remains available", response.getValue());
            assertEquals(2, response.getCount());
        }
    }

    @Test
    public void connectorLevelProtobufKeepsAnUnboundLocalService() throws Exception {
        // 连接器选择 Protobuf，同样不能让缺少资源的服务进入 gRPC 路由。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").option("serializeType", "pRoToBuF").protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> info = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").register();

            // 本地注册独立于协议是否接受，拒绝结果不产生端点。
            MetBindInfo<?> published = context.getServiceInfo(Echo.class);
            assertNotNull(published);
            assertEquals(info.getBindID(), published.getBindID());

            // 通过公开客户端优先调用本地提供者，协议拒绝不会撤销本地能力。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("local remains available", 1));
            assertEquals("local remains available", response.getValue());
            assertEquals(2, response.getCount());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonMountAcceptsAnOrdinaryJavaProviderWithoutProto() throws Exception {
        // JSON 挂载不需要 proto，两端仍使用独立容器验证真实网络能力。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("grpc").option("serializeType", "Json");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc").option("serializeType", "Json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            MetBindInfo<Echo> provider = server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").register();
            MetBindInfo<Echo> consumer = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            server.start();
            client.start();

            // 服务在启动前发布，启动后通过 HTTP/2 JSON 编码完成 RPC。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(consumer);
            Message response = remote.echo(Message.of("json without proto", 3));
            assertEquals("json without proto", response.getValue());
            assertEquals(4, response.getCount());
        }
    }

    @NxnRpc(name = "grpc")
    @NxnRpc(name = "rsf", version = "1")
    @Test
    public void selectingAnotherProtocolSkipsGrpcResourceValidation() throws Exception {
        // 同时配置 gRPC 与 RSF，随后将服务限制为 RSF。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> info = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").protocol("rsf").register();

            // 选择 RSF 的服务仍保留本地注册，不要求无关 gRPC 资源。
            MetBindInfo<?> published = context.getServiceInfo(Echo.class);
            assertNotNull(published);
            assertEquals(info.getBindID(), published.getBindID());

            // 通过本地代理执行所选接口，不需要 gRPC 的 proto 资源。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("selected rsf", 4));
            assertEquals("selected rsf", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @NxnRpc(name = "grpc")
    @NxnRpc(name = "rsf", version = "1")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingProtoDoesNotWithdrawTheRsfPublication() throws Exception {
        // 服务允许 RSF 与 gRPC，两种协议独立判断是否能接受这个 Java 接口。
        MetConfiguration incoming = this.configuration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<Echo> provider = server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").protocol("grpc", "rsf").register();
            MetBindInfo<Echo> consumer = client.publisher().rsfService(Echo.class).protocol("rsf").register();
            assertNotNull(server.getServiceInfo(provider.getBindID()));

            // 通过独立客户端真实调用仍可用的 RSF 端点，不能退化为本地直调。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(consumer);
            Message response = remote.echo(Message.of("rsf remains available", 2));
            assertEquals("rsf remains available", response.getValue());
            assertEquals(3, response.getCount());
            assertEquals(1, implementation.getCalls());
        }
    }

    @Test
    public void grpcOnTheClasspathDoesNotValidateAnUnmountedProtocol() throws Exception {
        // 类路径中存在 gRPC 插件，但当前实例并未挂载该协议。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> info = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").register();

            // 没有挂载的协议不参与发布，本地服务仍可正常注册。
            MetBindInfo<?> published = context.getServiceInfo(Echo.class);
            assertNotNull(published);
            assertEquals(info.getBindID(), published.getBindID());

            // 通过公开客户端优先调用本地提供者，协议拒绝不会撤销本地能力。
            Echo local = context.getRsfClient().getRemote(info);
            Message response = local.echo(Message.of("local remains available", 1));
            assertEquals("local remains available", response.getValue());
            assertEquals(2, response.getCount());
        }
    }

    @Test
    public void invalidProtocolResourceRejectsRegistration() throws Exception {
        // gRPC 的显式资源必须是 proto 资源，不能被忽略后改走其他契约。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").bindResource("grpc", new Object());

            // 发布失败会撤销本次登记，不能留下部分配置的服务。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(context.getServiceInfo(Echo.class));
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void boundProtoAllowsProviderRegistrationAndStartup() throws Exception {
        // 两端绑定同一个 proto，在容器启动前发布和订阅接口。
        MetConfiguration incoming = this.configuration();
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        GrpcProtoResource proto = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/grpc/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            MetBindInfo<Echo> provider = server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").bindResource("grpc", proto).register();
            MetBindInfo<Echo> consumer = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").bindResource("grpc", proto).register();
            server.start();
            client.start();

            // 启动后的网络调用实际使用绑定的 Protobuf 契约。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(consumer);
            Message response = remote.echo(Message.of("bound proto", 5));
            assertEquals("bound proto", response.getValue());
            assertEquals(6, response.getCount());
        }
    }

    private MethodDescriptor<Empty, Empty> echoMethod() {
        // 只构建官方对端的方法描述；被拒绝的服务不会进入请求正文解码。
        return MethodDescriptor.<Empty, Empty>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName("example.Echo/echo") //
                .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())) //
                .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())).build();
    }

    private MetConfiguration configuration() {
        // 共用最小 gRPC 监听配置，保留插件默认的 Protobuf 选择。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        return configuration;
    }
}
