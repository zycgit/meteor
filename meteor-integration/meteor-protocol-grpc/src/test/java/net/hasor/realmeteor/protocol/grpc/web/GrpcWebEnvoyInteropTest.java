/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

/** Meteor 客户端通过真实 Envoy gRPC-Web 过滤器调用独立 gRPC Java 服务端。 */
public class GrpcWebEnvoyInteropTest {
    @Test(timeout = 60000)
    public void binaryUnaryCallsOfficialServerThroughEnvoy() throws Exception {
        // 官方服务只处理标准 gRPC；Envoy 独立完成浏览器协议与 HTTP/2 的转换。
        var echo = this.method("echo", MethodDescriptor.MethodType.UNARY);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, response) -> {
            response.onNext(Message.of("envoy:" + request.getValue(), request.getCount() + 1));
            response.onCompleted();
        });
        var definition = ServerServiceDefinition.builder("web.Echo").addMethod(echo, handler).build();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(definition).build().start();
        MetConfiguration configuration = this.configuration(false);
        try (AutoCloseable serverCleanup = () -> {
            server.shutdownNow();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Official gRPC server did not terminate");
            }
        }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort()); //
             MetContext client = configuration.buildContext()) {
            // 客户端从 bootstrap 启动后，绑定普通接口与 Protobuf 服务定义。
            client.start();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 调用实际 Envoy 端口，响应必须经过 gRPC-Web 二进制帧解码。
            InterAddress address = new InterAddress("grpc-web", "127.0.0.1", envoy.getPort(), "default");
            GrpcWebService remote = client.getRsfClient(address).getRemote(service);
            Message result = remote.echo(Message.of("binary", 7));
            assertEquals("envoy:binary", result.getValue());
            assertEquals(8, result.getCount());
        }
    }

    @Test(timeout = 60000)
    public void textUnaryCallsOfficialServerThroughEnvoy() throws Exception {
        // 官方服务返回包含 Unicode 的消息，用真实 Envoy 验证文本模式的 Base64 传输。
        var echo = this.method("echo", MethodDescriptor.MethodType.UNARY);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, response) -> {
            response.onNext(Message.of("envoy:" + request.getValue(), request.getCount() + 1));
            response.onCompleted();
        });
        var definition = ServerServiceDefinition.builder("web.Echo").addMethod(echo, handler).build();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(definition).build().start();
        MetConfiguration configuration = this.configuration(true);
        try (AutoCloseable serverCleanup = () -> {
            server.shutdownNow();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Official gRPC server did not terminate");
            }
        }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort()); //
             MetContext client = configuration.buildContext()) {
            // 完成启动和接口订阅，调用端选择 grpc-web-text 编码。
            client.start();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 文本响应由独立 Envoy 编码，Meteor 必须完整还原 Unicode 和字段值。
            InterAddress address = new InterAddress("grpc-web", "127.0.0.1", envoy.getPort(), "default");
            GrpcWebService remote = client.getRsfClient(address).getRemote(service);
            Message result = remote.echo(Message.of("文本 🙂", 12));
            assertEquals("envoy:文本 🙂", result.getValue());
            assertEquals(13, result.getCount());
        }
    }

    @Test(timeout = 60000)
    public void textServerStreamReceivesOfficialMessagesThroughEnvoy() throws Exception {
        // 标准 gRPC 服务端依次输出三条消息，由 Envoy 转换为文本流及结束帧。
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var handler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, response) -> {
            response.onNext(Message.of(request.getValue(), 2));
            response.onNext(Message.of(request.getValue(), 5));
            response.onNext(Message.of(request.getValue(), 9));
            response.onCompleted();
        });
        var definition = ServerServiceDefinition.builder("web.Echo").addMethod(list, handler).build();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(definition).build().start();
        MetConfiguration configuration = this.configuration(true);
        try (AutoCloseable serverCleanup = () -> {
            server.shutdownNow();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Official gRPC server did not terminate");
            }
        }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort()); //
             MetContext client = configuration.buildContext()) {
            // 从公开启动入口创建客户端，订阅返回 Flow.Publisher 的普通 Java 接口。
            client.start();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 等待流正常结束，并校验顺序，避免仅验证首条消息就提前通过。
            InterAddress address = new InterAddress("grpc-web", "127.0.0.1", envoy.getPort(), "default");
            GrpcWebService remote = client.getRsfClient(address).getRemote(service);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("stream", 0)), values::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, values.size());
            assertEquals(List.of(2, 5, 9), values.stream().map(Message::getCount).toList());
            assertEquals("stream", values.get(2).getValue());
        }
    }

    @Test(timeout = 60000)
    public void textUnaryPreservesOfficialFailureThroughEnvoy() throws Exception {
        // 独立服务端返回标准 PERMISSION_DENIED，由 Envoy 将状态转为正文末尾帧。
        var fail = this.method("fail", MethodDescriptor.MethodType.UNARY);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, response) -> {
            response.onError(Status.PERMISSION_DENIED.withDescription("envoy denied 中文 + %").asRuntimeException());
        });
        var definition = ServerServiceDefinition.builder("web.Echo").addMethod(fail, handler).build();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(definition).build().start();
        MetConfiguration configuration = this.configuration(true);
        try (AutoCloseable serverCleanup = () -> {
            server.shutdownNow();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Official gRPC server did not terminate");
            }
        }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort()); //
             MetContext client = configuration.buildContext()) {
            // 通过 bootstrap 启动客户端并订阅服务，保持调用链与用户代码一致。
            client.start();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 错误必须保留原始状态和描述，不能把故障退化为连接超时。
            InterAddress address = new InterAddress("grpc-web", "127.0.0.1", envoy.getPort(), "default");
            GrpcWebService remote = client.getRsfClient(address).getRemote(service);
            Exception failure = assertThrows(Exception.class, () -> remote.fail(Message.of("fail", 0)));
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(7, status.getCode());
            assertEquals("envoy denied 中文 + %", status.getDescription());
        }
    }

    @Test(timeout = 60000)
    public void textStreamCancellationReachesOfficialServerThroughEnvoy() throws Exception {
        // 官方服务发送首条消息后保持流打开，独立观察上游 HTTP/2 调用的取消事件。
        CountDownLatch cancelled = new CountDownLatch(1);
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var handler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, response) -> {
            ServerCallStreamObserver<Message> stream = (ServerCallStreamObserver<Message>) response;
            stream.setOnCancelHandler(cancelled::countDown);
            stream.onNext(Message.of("first", 1));
        });
        var definition = ServerServiceDefinition.builder("web.Echo").addMethod(list, handler).build();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                .addService(definition).build().start();
        MetConfiguration configuration = this.configuration(true);
        try (AutoCloseable serverCleanup = () -> {
            server.shutdownNow();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Official gRPC server did not terminate");
            }
        }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort()); //
             MetContext client = configuration.buildContext()) {
            // 启动客户端并完成普通接口订阅，调用真实代理获得消息流。
            client.start();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();
            InterAddress address = new InterAddress("grpc-web", "127.0.0.1", envoy.getPort(), "default");
            GrpcWebService remote = client.getRsfClient(address).getRemote(service);

            // 收到首条消息后取消订阅，确认取消穿过 Envoy 到达独立服务，而非依赖关闭客户端。
            BasicFuture<Void> received = new BasicFuture<>();
            remote.list(Message.of("cancel", 0)).subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription value) {
                    this.subscription = value;
                    value.request(1);
                }

                @Override
                public void onNext(Message value) {
                    this.subscription.cancel();
                    received.completed(null);
                }

                @Override
                public void onError(Throwable failure) {
                    received.failed(failure);
                }

                @Override
                public void onComplete() {
                    received.failed(new IllegalStateException("Stream completed before cancellation"));
                }
            });
            received.get(5, TimeUnit.SECONDS);
            assertTrue("Expected upstream gRPC cancellation", cancelled.await(5, TimeUnit.SECONDS));
        }
    }

    private MetConfiguration configuration(boolean text) {
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.setDefaultProtocol("grpc-web");
        configuration.connector("http").protocol("grpc-web") //
                .contextPath("/web") //
                .option("webText", text) //
                .option("streamBody", true);
        return configuration;
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
    }

    private MethodDescriptor<Message, Message> method(String name, MethodDescriptor.MethodType type) {
        // 独立使用 Protobuf 的字段号编解码，不复用 Meteor 插件的 Codec 或会话实现。
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(Message message) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                    if (message.getValue() != null) {
                        output.writeString(7, message.getValue());
                    }
                    output.writeInt32(2, message.getCount());
                    output.flush();
                    return new ByteArrayInputStream(bytes.toByteArray());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            @Override
            public Message parse(InputStream stream) {
                try {
                    CodedInputStream input = CodedInputStream.newInstance(stream);
                    Message message = new Message();
                    int tag;
                    while ((tag = input.readTag()) != 0) {
                        if (tag == 58) {
                            message.setValue(input.readStringRequireUtf8());
                        } else if (tag == 16) {
                            message.setCount(input.readInt32());
                        } else {
                            input.skipField(tag);
                        }
                    }
                    return message;
                } catch (Exception failure) {
                    throw new IllegalArgumentException(failure);
                }
            }
        };
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setType(type) //
                .setFullMethodName("web.Echo/" + name) //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }
}
