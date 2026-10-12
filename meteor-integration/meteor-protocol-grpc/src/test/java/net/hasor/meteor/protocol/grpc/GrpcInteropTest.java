/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.neta.channel.ProtoBuildContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcInteropTest {
    public interface Echo {
        Message echo(Message request);

        Message fail(Message request);

        Message slow(Message request);
    }

    public static class Message {
        private String value;
        private int    count;

        public Message() {
        }

        public Message(String value, int count) {
            this.value = value;
            this.count = count;
        }

        public String getValue() {
            return this.value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public int getCount() {
            return this.count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class EchoImpl implements Echo {
        private final AtomicInteger calls = new AtomicInteger();

        public Message echo(Message request) {
            this.calls.incrementAndGet();
            return new Message(request.getValue(), request.getCount() + 1);
        }

        public Message fail(Message request) {
            throw new IllegalArgumentException("business failure 中文");
        }

        public Message slow(Message request) {
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return this.echo(request);
        }
    }

    @Test
    public void officialProtobufClientCallsOrdinaryJavaServiceThroughSharedHttpPort() throws Exception {
        // 通过公开配置发布普通接口；资源只绑定 .proto，不使用生成的 Java 服务基类。
        MetConfiguration configuration = this.configuration();
        var http = configuration.connector("http").bind("127.0.0.1", 0);
        http.protocol("grpc").contextPath("/grpc");
        http.protocol("hprose").contextPath("/hprose");
        EchoImpl implementation = new EchoImpl();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, implementation).aliasName("grpc", "example.Echo").aliasName("Hprose", "exampleEcho").serialize("Protobuf").bindResource(this.resource()).register();
            context.start();
            int port = context.bindAddress("grpc").getPort();
            assertEquals(port, context.bindAddress("hprose").getPort());
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 官方客户端的 marshaller 独立使用字段号 7 和 2，验证真实 Protobuf 兼容性。
                MethodDescriptor<Message, Message> echo = this.method("grpc/example.Echo/echo", false);
                Message result = this.call(peer, echo, new Message("中文", 3));
                assertEquals("中文", result.getValue());
                assertEquals(4, result.getCount());
                assertEquals(1, implementation.calls.get());

                // 业务异常作为 gRPC status 返回，保留错误说明，随后仍能调用。
                MethodDescriptor<Message, Message> fail = this.method("grpc/example.Echo/fail", false);
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> this.call(peer, fail, new Message("failure", 0)));
                assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
                assertTrue(error.getStatus().getDescription().contains("business failure"));
                assertEquals("again", this.call(peer, echo, new Message("again", 1)).getValue());

                // HTTP/1 的 Hprose 挂载实际处理请求，共用监听端口。
                Message hprose = this.hprose(port, new Message("http1", 5));
                assertEquals("http1", hprose.getValue());
                assertEquals(6, hprose.getCount());

                // 错用 HTTP/1 访问 gRPC 挂载时明确拒绝，不能执行一次无有效 gRPC 响应的业务调用。
                URL wrongVersion = new URL("http://127.0.0.1:" + port + "/grpc/example.Echo/echo");
                HttpURLConnection invalid = (HttpURLConnection) wrongVersion.openConnection();
                invalid.setReadTimeout(3000);
                try {
                    assertEquals(505, invalid.getResponseCode());
                    assertEquals(3, implementation.calls.get());
                } finally {
                    invalid.disconnect();
                }
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void meteorClientCallsOfficialServerWithoutGeneratedClasses() throws Exception {
        MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", false);
        ServerServiceDefinition service = ServerServiceDefinition.builder("example.Echo").addMethod(method, ServerCalls.asyncUnaryCall((request, reply) -> {
            reply.onNext(new Message(request.getValue(), request.getCount() + 5));
            reply.onCompleted();
        })).build();
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = this.clientConfiguration("/").buildContext()) {
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class).aliasName("grpc", "example.Echo").serialize("pRoToBuF").bindResource(this.resource()).register();
            client.start();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(info);
            Message result = remote.echo(new Message("outgoing", 2));
            assertEquals("outgoing", result.getValue());
            assertEquals(7, result.getCount());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void meteorJsonClientCallsOfficialServerWithCustomMarshaller() throws Exception {
        MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", true);
        ServerServiceDefinition service = ServerServiceDefinition.builder("example.Echo").addMethod(method, ServerCalls.asyncUnaryCall((request, reply) -> {
            reply.onNext(new Message(request.getValue(), request.getCount() + 5));
            reply.onCompleted();
        })).build();
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = this.clientConfiguration("/").buildContext()) {
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class).aliasName("grpc", "example.Echo").serialize("Json").register();
            client.start();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(info);
            Message result = remote.echo(new Message("outgoing", 2));
            assertEquals("outgoing", result.getValue());
            assertEquals(7, result.getCount());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void jsonCallsAndConcurrentMeteorCallsUseTheExistingCaller() throws Exception {
        // 通过正式入口验证并发调用；connect 当前为每次调用创建独立会话。
        CountingPipeline.clients.set(0);
        CountingPipeline.servers.set(0);
        MetConfiguration configuration = this.configuration();
        var incoming = configuration.connector("http").bind("127.0.0.1", 0);
        incoming.option("transportPipeline", CountingPipeline.class.getName()).protocol("grpc");
        MetConfiguration outgoing = this.configuration();
        var connector = outgoing.connector("http").option("httpVersion", 2);
        connector.option("transportPipeline", CountingPipeline.class.getName()).protocol("grpc");

        // 暂停第一个业务调用，让后续调用先返回，明确验证调用重叠与响应乱序。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EchoImpl service = new EchoImpl() {
            @Override
            public Message echo(Message request) {
                if (request.getCount() == 0) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release the first call");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return super.echo(request);
            }
        };
        try (MetContext server = configuration.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Echo.class, service).aliasName("grpc", "example.Echo").serialize("Json").register();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class).aliasName("grpc", "example.Echo").serialize("Json").register();
            server.start();
            client.start();
            var remote = client.getRsfClient(server.bindAddress("grpc"));
            List<MetFuture> calls = new ArrayList<>();
            Class<?>[] types = { Message.class };
            for (int i = 0; i < 20; i++) {
                Object[] arguments = { new Message("call-" + i, i) };
                calls.add(remote.asyncInvoke(info, "echo", types, arguments));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (int i = 1; i < calls.size(); i++) {
                Message response = (Message) calls.get(i).getData(5, TimeUnit.SECONDS);
                assertEquals("call-" + i, response.getValue());
                assertEquals(i + 1, response.getCount());
            }
            assertFalse(calls.get(0).isDone());
            assertEquals(calls.size(), CountingPipeline.clients.get());
            assertEquals(calls.size(), CountingPipeline.servers.get());

            // 最后放行第一个调用，仍必须匹配它自己的响应。
            release.countDown();
            Message first = (Message) calls.get(0).getData(5, TimeUnit.SECONDS);
            assertEquals("call-0", first.getValue());
            assertEquals(1, first.getCount());
        } finally {
            release.countDown();
        }
    }

    public static final class CountingPipeline implements TransportPipeline {
        private static final AtomicInteger clients = new AtomicInteger();
        private static final AtomicInteger servers = new AtomicInteger();

        @Override
        public void initialize(ConnectorConfig config, ProtoBuildContext stack) {
            if (stack.getChannel().isClient()) {
                clients.incrementAndGet();
            } else {
                servers.incrementAndGet();
            }
        }
    }

    @Test
    public void officialCustomMarshallerUsesThePublishedJsonSerializer() throws Exception {
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).aliasName("grpc", "example.Echo").serialize("Json").register();
            context.start();
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", true);
                Message response = this.call(peer, method, new Message("custom JSON", 7));
                assertEquals("custom JSON", response.getValue());
                assertEquals(8, response.getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void cancellationAndDeadlineOnlyEndTheirOwnHttp2Stream() throws Exception {
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        CountDownLatch release = new CountDownLatch(1);
        LinkedBlockingQueue<String> entered = new LinkedBlockingQueue<>();
        EchoImpl implementation = new EchoImpl() {
            public Message slow(Message request) {
                entered.add(request.getValue());
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the service");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return request;
            }
        };
        try (MetContext server = configuration.buildContext(); MetContext client = this.clientConfiguration("/").buildContext()) {
            server.publisher().rsfService(Echo.class, implementation).aliasName("grpc", "example.Echo").serialize("Json").register();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class).aliasName("grpc", "example.Echo").serialize("Json").timeout(500).register();
            server.start();
            client.start();
            var remote = client.getRsfClient(server.bindAddress("grpc"));
            Class<?>[] types = { Message.class };

            // 先让请求真正进入服务，再验证取消；已进入业务不强制中断。
            MetFuture cancelled = remote.asyncInvoke(info, "slow", types, new Object[] { new Message("cancel", 0) });
            assertEquals("cancel", entered.poll(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel());
            MetFuture expired = remote.asyncInvoke(info, "slow", types, new Object[] { new Message("timeout", 0) });
            assertEquals("timeout", entered.poll(3, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> expired.getData(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MetTimeoutException);

            // 同一个客户端继续调用，取消与迟到结果不会污染后续响应。
            release.countDown();
            Message result = remote.getRemote(info).echo(new Message("after", 4));
            assertEquals("after", result.getValue());
            assertEquals(5, result.getCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void protobufLargeMessageCrossesHttp2FlowControlWindows() throws Exception {
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).aliasName("grpc", "example.Echo").serialize("Protobuf").bindResource(this.resource()).register();
            context.start();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", context.bindAddress("grpc").getPort()).usePlaintext().build();
            try {
                String value = "message".repeat(40_000);
                Message response = this.call(peer, this.method("example.Echo/echo", false), new Message(value, 1));
                assertEquals(value, response.getValue());
                assertEquals(2, response.getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private Message hprose(int port, Message request) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('C');
        HproseWriter writer = new HproseWriter(body);
        writer.writeString("exampleEcho_echo");
        writer.reset();
        writer.writeArray(new Object[] { request });
        body.write('z');
        URL endpoint = new URL("http://127.0.0.1:" + port + "/hprose");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try {
            try (var output = connection.getOutputStream()) {
                output.write(body.toByteArray());
            }
            try (InputStream input = connection.getInputStream()) {
                int tag = input.read();
                if (tag == 'E') {
                    fail(new HproseReader(input).readString());
                }
                assertEquals('R', tag);
                Message response = new HproseReader(input).unserialize(Message.class);
                assertEquals('z', input.read());
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration config = new MetConfiguration();
        config.setDefaultProtocol("grpc");
        config.setDefaultTimeout(3000);
        config.setQueueMinPoolSize(2);
        return config;
    }

    private MetConfiguration clientConfiguration(String mount) {
        MetConfiguration config = this.configuration();
        config.connector("http").option("httpVersion", 2).protocol("grpc").contextPath(mount);
        return config;
    }

    private GrpcProtoResource resource() throws IOException {
        return new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
    }

    private Message call(ManagedChannel channel, MethodDescriptor<Message, Message> method, Message request) {
        CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
        return ClientCalls.blockingUnaryCall(channel, method, options, request);
    }

    private MethodDescriptor<Message, Message> method(String name, boolean json) {
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(Message value) {
                try {
                    if (json) {
                        return new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(value));
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    CodedOutputStream output = CodedOutputStream.newInstance(buffer);
                    output.writeString(7, value.getValue());
                    output.writeInt32(2, value.getCount());
                    output.flush();
                    return new ByteArrayInputStream(buffer.toByteArray());
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }

            public Message parse(InputStream stream) {
                try {
                    if (json) {
                        return new ObjectMapper().readValue(stream, Message.class);
                    }
                    Message message = new Message("", 0);
                    CodedInputStream input = CodedInputStream.newInstance(stream);
                    int tag;
                    while ((tag = input.readTag()) != 0) {
                        switch (tag) {
                            case 58 -> message.setValue(input.readStringRequireUtf8());
                            case 16 -> message.setCount(input.readInt32());
                            default -> input.skipField(tag);
                        }
                    }
                    return message;
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        return MethodDescriptor.<Message, Message>newBuilder().setType(MethodDescriptor.MethodType.UNARY).setFullMethodName(name).setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
    }
}
