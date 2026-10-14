/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
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
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.neta.channel.ProtoBuildContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcInteropTest {

    @Test
    public void protobufAndHproseShareOneHttpPort() throws Exception {
        // 在同一个 HTTP 监听端口挂载 gRPC 和 Hprose，服务资源只绑定 proto。
        MetConfiguration configuration = this.configuration();
        var http = configuration.connector("http").bind("127.0.0.1", 0);
        http.protocol("grpc").contextPath("/grpc");
        http.protocol("hprose").contextPath("/hprose");
        EchoImpl implementation = new EchoImpl();
        try (MetContext context = configuration.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            context.start();

            var publication = context.publisher().rsfService(Echo.class, implementation);
            publication //
                    .aliasName("grpc", "example.Echo") //
                    .aliasName("Hprose", "exampleEcho") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            int port = context.bindAddress("grpc").getPort();
            assertEquals(port, context.bindAddress("hprose").getPort());
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 官方客户端的 marshaller 独立使用字段号 7 和 2，验证真实 Protobuf 兼容性。
                MethodDescriptor<Message, Message> echo = this.method("grpc/example.Echo/echo", false);
                Message result = this.call(peer, echo, new Message("中文", 3));
                assertEquals("中文", result.getValue());
                assertEquals(4, result.getCount());
                assertEquals(1, implementation.getCalls());

                // HTTP/1 的 Hprose 挂载实际处理请求，共用监听端口。
                Message hprose = this.hprose(port, new Message("http1", 5));
                assertEquals("http1", hprose.getValue());
                assertEquals(6, hprose.getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void officialClientSeesBusinessFailureWithoutPoisoningTheNextCall() throws Exception {
        // 发布会抛出业务异常的服务，验证标准错误状态与后续调用隔离。
        MetConfiguration configuration = this.configuration();
        var http = configuration.connector("http").bind("127.0.0.1", 0);
        http.protocol("grpc").contextPath("/grpc");
        http.protocol("hprose").contextPath("/hprose");
        EchoImpl implementation = new EchoImpl();
        try (MetContext context = configuration.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            context.start();

            var publication = context.publisher().rsfService(Echo.class, implementation);
            publication //
                    .aliasName("grpc", "example.Echo") //
                    .aliasName("Hprose", "exampleEcho") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            int port = context.bindAddress("grpc").getPort();
            assertEquals(port, context.bindAddress("hprose").getPort());
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 业务异常作为 gRPC status 返回，保留错误说明，随后仍能调用。
                MethodDescriptor<Message, Message> fail = this.method("grpc/example.Echo/fail", false);
                Message request = new Message("failure", 0);
                var error = assertThrows(StatusRuntimeException.class, () -> this.call(peer, fail, request));
                assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
                String description = error.getStatus().getDescription();
                assertTrue(error.getMessage(), description.contains("business failure"));

                // 同一通道再次发起正常调用，之前的失败不能污染响应。
                MethodDescriptor<Message, Message> echo = this.method("grpc/example.Echo/echo", false);
                Message next = this.call(peer, echo, new Message("again", 1));
                assertEquals("again", next.getValue());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void http1CannotInvokeAGrpcService() throws Exception {
        // 在共享 HTTP 端口发布 gRPC，验证 HTTP/1 请求不能执行 gRPC 业务。
        MetConfiguration configuration = this.configuration();
        var http = configuration.connector("http").bind("127.0.0.1", 0);
        http.protocol("grpc").contextPath("/grpc");
        http.protocol("hprose").contextPath("/hprose");
        EchoImpl implementation = new EchoImpl();
        try (MetContext context = configuration.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            context.start();

            var publication = context.publisher().rsfService(Echo.class, implementation);
            publication //
                    .aliasName("grpc", "example.Echo") //
                    .aliasName("Hprose", "exampleEcho") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            int port = context.bindAddress("grpc").getPort();
            assertEquals(port, context.bindAddress("hprose").getPort());
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 错用 HTTP/1 访问 gRPC 挂载时明确拒绝，不能执行一次无有效 gRPC 响应的业务调用。
                URL wrongVersion = new URL("http://127.0.0.1:" + port + "/grpc/example.Echo/echo");
                HttpURLConnection invalid = (HttpURLConnection) wrongVersion.openConnection();
                invalid.setReadTimeout(3000);
                try {
                    assertEquals(505, invalid.getResponseCode());
                    assertEquals(0, implementation.getCalls());
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
        // 独立搭建官方 Protobuf 服务端，消息按 proto 字段号编解码。
        MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", false);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, reply) -> {
            Message response = new Message(request.getValue(), request.getCount() + 5);
            reply.onNext(response);
            reply.onCompleted();
        });
        ServerServiceDefinition service = ServerServiceDefinition.builder("example.Echo") //
                .addMethod(method, handler) //
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = this.clientConfiguration("/").buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            client.start();

            var publication = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = publication //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("pRoToBuF") //
                    .bindResource(this.resource()) //
                    .register();

            // 使用官方服务的真实端口调用接口，验证返回值经过正确的消息编码。
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
        // 独立搭建官方服务端，通过自定义 marshaller 使用 JSON 消息。
        MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", true);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, reply) -> {
            Message response = new Message(request.getValue(), request.getCount() + 5);
            reply.onNext(response);
            reply.onCompleted();
        });
        ServerServiceDefinition service = ServerServiceDefinition.builder("example.Echo") //
                .addMethod(method, handler) //
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = this.clientConfiguration("/").buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            client.start();

            var publication = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = publication.aliasName("grpc", "example.Echo").serialize("Json").register();

            // 使用官方服务的真实端口调用接口，验证返回值经过正确的消息编码。
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
    public void unaryIncrementalResponseWaitsForBodyAndTrailers() throws Exception {
        // 两端启用增量正文，在客户端限制单条消息大小。
        MetConfiguration incoming = this.configuration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc").option("streamBody", true);
        MetConfiguration outgoing = this.configuration();
        var mount = outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        mount.option("streamBody", true).option("maxMessageSize", 128);
        EchoImpl implementation = new EchoImpl() {
            public Message echo(Message request) {
                return new Message("x".repeat(request.getCount()), request.getCount());
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            server.start();
            client.start();

            var publication = server.publisher().rsfService(Echo.class, implementation);
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = subscription.aliasName("grpc", "example.Echo").serialize("Json").register();
            var caller = client.getRsfClient(server.bindAddress("grpc"));
            Echo remote = caller.getRemote(info);

            // 增量响应结束后才交付普通调用结果。
            Message response = remote.echo(new Message("", 3));
            assertEquals("xxx", response.getValue());
        }
    }

    @Test
    public void oversizedIncrementalResponseDoesNotPoisonTheNextCall() throws Exception {
        // 两端启用增量正文，准备验证客户端的 128 字节消息上限。
        MetConfiguration incoming = this.configuration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc").option("streamBody", true);
        MetConfiguration outgoing = this.configuration();
        var mount = outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        mount.option("streamBody", true).option("maxMessageSize", 128);
        EchoImpl implementation = new EchoImpl() {
            public Message echo(Message request) {
                return new Message("x".repeat(request.getCount()), request.getCount());
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            server.start();
            client.start();

            var publication = server.publisher().rsfService(Echo.class, implementation);
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = subscription.aliasName("grpc", "example.Echo").serialize("Json").register();
            var caller = client.getRsfClient(server.bindAddress("grpc"));
            Echo remote = caller.getRemote(info);

            // 消息超限仅结束当前调用。
            Class<?>[] types = { Message.class };
            Object[] arguments = { new Message("", 512) };
            MetFuture oversized = caller.asyncInvoke(info, "echo", types, arguments);
            var failure = assertThrows(ExecutionException.class, () -> oversized.get(3, TimeUnit.SECONDS));
            String message = failure.getCause().getMessage();
            assertTrue(message.contains("maxMessageSize"));

            // 当前消息被拒绝后，符合长度限制的下一次调用仍须成功。
            Message next = remote.echo(new Message("", 1));
            assertEquals("x", next.getValue());
        }
    }

    @Test
    public void concurrentJsonCallsKeepResponsesMatched() throws Exception {
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
            // 先启动容器，再通过公开 API 发布或订阅服务。
            server.start();
            client.start();

            var publication = server.publisher().rsfService(Echo.class, service);
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = subscription.aliasName("grpc", "example.Echo").serialize("Json").register();

            // 一次提交多个异步调用，首个调用仍受闩锁阻塞。
            var remote = client.getRsfClient(server.bindAddress("grpc"));
            List<MetFuture> calls = new ArrayList<>();
            Class<?>[] types = { Message.class };
            for (int i = 0; i < 20; i++) {
                Object[] arguments = { new Message("call-" + i, i) };
                calls.add(remote.asyncInvoke(info, "echo", types, arguments));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            // 后续调用先结束，每个响应仍必须匹配对应请求。
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
        // 配置 JSON 编码服务，准备由官方客户端的自定义 marshaller 调用。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            context.start();

            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();

            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 官方客户端以 JSON 消息调用，检查 Meteor 使用发布时选择的序列化器。
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
    public void cancellationOnlyEndsItsOwnHttp2Stream() throws Exception {
        // 配置可阻塞的业务实现，让取消发生在服务执行期间。
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
        try (MetContext server = configuration.buildContext(); //
             MetContext client = this.clientConfiguration("/").buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            server.start();
            client.start();

            var publication = server.publisher().rsfService(Echo.class, implementation);
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = subscription //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .timeout(500) //
                    .register();

            var remote = client.getRsfClient(server.bindAddress("grpc"));
            Class<?>[] types = { Message.class };

            // 等业务进入阻塞段后取消，确保验证的是在途调用。
            Object[] arguments = { new Message("cancel", 0) };
            MetFuture cancelled = remote.asyncInvoke(info, "slow", types, arguments);
            assertEquals("cancel", entered.poll(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel());

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
    public void deadlineOnlyEndsItsOwnHttp2Stream() throws Exception {
        // 配置可阻塞的业务实现，让调用期限早于服务返回。
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
        try (MetContext server = configuration.buildContext(); //
             MetContext client = this.clientConfiguration("/").buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            server.start();
            client.start();

            var publication = server.publisher().rsfService(Echo.class, implementation);
            publication.aliasName("grpc", "example.Echo").serialize("Json").register();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> info = subscription //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .timeout(500) //
                    .register();

            var remote = client.getRsfClient(server.bindAddress("grpc"));
            Class<?>[] types = { Message.class };

            // 等待已进入业务的调用超时，服务端随后返回的结果应被隔离。
            Object[] arguments = { new Message("timeout", 0) };
            MetFuture expired = remote.asyncInvoke(info, "slow", types, arguments);
            assertEquals("timeout", entered.poll(3, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> expired.getData(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MetTimeoutException);

            // 同一个客户端继续调用，超时与迟到结果不会污染后续响应。
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
        // 发布 Protobuf 服务，准备官方客户端发送跨越多个 HTTP/2 窗口的大消息。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            // 先启动容器，再通过公开 API 发布或订阅服务。
            context.start();

            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 大消息经过多个流控窗口后，字段内容与计数都必须完整。
                String value = "message".repeat(40_000);
                MethodDescriptor<Message, Message> method = this.method("example.Echo/echo", false);
                Message request = new Message(value, 1);
                Message response = this.call(peer, method, request);
                assertEquals(value, response.getValue());
                assertEquals(2, response.getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private Message hprose(int port, Message request) throws Exception {
        // 使用官方 Hprose 编码器独立生成调用报文。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('C');
        HproseWriter writer = new HproseWriter(body);
        writer.writeString("exampleEcho_echo");
        writer.reset();
        writer.writeArray(new Object[] { request });
        body.write('z');

        // 经 HTTP/1 访问共享端口上的 Hprose 挂载点。
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

            // 对端返回完整的结果标签、消息和结束标签。
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
        // 为互通测试设置明确的协议、期限和服务执行线程数。
        MetConfiguration config = new MetConfiguration();
        config.setDefaultProtocol("grpc");
        config.setDefaultTimeout(3000);
        config.setQueueMinPoolSize(2);
        return config;
    }

    private MetConfiguration clientConfiguration(String mount) {
        // 客户端只配置出站 HTTP/2 与协议挂载，不创建监听端口。
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
        // 官方对端独立编码消息，避免双方复用 Meteor 实现掩盖兼容性问题。
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(Message value) {
                try {
                    if (json) {
                        byte[] jsonBytes = new ObjectMapper().writeValueAsBytes(value);
                        return new ByteArrayInputStream(jsonBytes);
                    }

                    // Protobuf 使用 proto 中声明的字段号 7 和 2。
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

                    // 未知 Protobuf 字段按标准规则跳过，不影响已知字段。
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
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName(name) //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }
}
