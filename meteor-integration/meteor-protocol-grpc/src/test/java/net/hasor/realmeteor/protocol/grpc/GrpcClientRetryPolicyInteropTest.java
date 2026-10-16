/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.PolicyService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用独立官方服务端观察物理尝试，业务侧仍只有一次普通 Meteor 调用。 */
public class GrpcClientRetryPolicyInteropTest {
    private static final Metadata.Key<String> PREVIOUS = //
            Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> PUSHBACK = //
            Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER);
    private static final String               RETRY    = """
            {"methodConfig":[{"name":[{"service":"test.Policy"}],"retryPolicy":{
              "maxAttempts":3,"initialBackoff":"0.01s","maxBackoff":"0.1s",
              "backoffMultiplier":2,"retryableStatusCodes":["UNAVAILABLE"]}}]}
            """;
    private static final String               HEDGE    = """
            {"methodConfig":[{"name":[{"service":"test.Policy"}],"hedgingPolicy":{
              "maxAttempts":3,"hedgingDelay":"0.1s","nonFatalStatusCodes":["UNAVAILABLE"]}}]}
            """;

    @Test(timeout = 20000)
    public void officialClientRetriesMeteorFailureBeforeAnyResponseMetadata() throws Exception {
        // 服务先返回两次可重试错误，第三次成功；对端使用官方重试策略。
        AtomicInteger attempts = new AtomicInteger();
        EchoImpl service = new EchoImpl() {
            public Message echo(Message request) {
                if (attempts.incrementAndGet() < 3) {
                    throw new GrpcStatusException(14, "retry later", null, null);
                }
                return request;
            }
        };
        MetConfiguration configuration = new MetConfiguration();
        configuration //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        try (MetContext context = configuration.buildContext()) {
            // 从公开入口发布服务，官方客户端随后加载重试配置。
            context.start();
            var publication = context.publisher().rsfService(Echo.class, service);
            publication.aliasName("grpc", "test.Policy").serialize("Json").register();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, ?> policy = mapper.readValue(RETRY, new TypeReference<>() {
            });
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .defaultServiceConfig(policy) //
                    .enableRetry() //
                    .build();
            try {
                var method = MethodDescriptor.<Message, Message>newBuilder();
                method.setType(MethodDescriptor.MethodType.UNARY) //
                        .setFullMethodName("test.Policy/echo");
                MethodDescriptor.Marshaller<Message> marshaller = Peer.marshaller();
                method.setRequestMarshaller(marshaller).setResponseMarshaller(marshaller);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                // 用户只发起一次请求，服务计数用于观察协议层的三次尝试。
                Message request = Message.of("official", 1);
                var descriptor = method.build();
                Message result = ClientCalls.blockingUnaryCall(channel, descriptor, options, request);
                assertEquals("official", result.getValue());
                assertEquals(3, attempts.get());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void explicitPolicyRetriesTrailersOnlyAndReportsAttemptMetadata() throws Exception {
        // 官方服务仅在第三次尝试成功，同时记录每次尝试的 previous 元数据。
        AtomicInteger attempts = new AtomicInteger();
        List<String> previous = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            previous.add(String.valueOf(headers.get(PREVIOUS)));
            return this.requests(call, (values, ignored) -> {
                if (attempts.incrementAndGet() < 3) {
                    call.close(Status.UNAVAILABLE, new Metadata());
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 两次 trailers-only 错误后成功，调用参数只构建一次。
            Message result = remote.echo(Message.of("retry", 7));
            assertEquals("retry", result.getValue());
            assertEquals(List.of("null", "1", "2"), previous);
            assertEquals(3, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void noRetryPolicyPerformsOnlyOneAttempt() throws Exception {
        // 官方对端始终返回 UNAVAILABLE，Meteor 未配置重试策略。
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                call.close(Status.UNAVAILABLE, new Metadata());
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration("{}", 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message defaultFailureRequest = Message.of("default", 0);
            Object[] defaultFailureArguments = { defaultFailureRequest };
            MetFuture defaultFailure = caller.asyncInvoke(info, "echo", parameterTypes, defaultFailureArguments);
            this.failure(defaultFailure, 14);
            assertEquals(1, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void committedHeadersPreventRetry() throws Exception {
        // 官方对端先提交响应头，再返回原本可重试的错误。
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                call.sendHeaders(new Metadata());
                call.close(Status.UNAVAILABLE, new Metadata());
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message committedFailureRequest = Message.of("headers", 0);
            Object[] committedFailureArguments = { committedFailureRequest };
            MetFuture committedFailure = caller.asyncInvoke(info, "echo", parameterTypes, committedFailureArguments);
            this.failure(committedFailure, 14);
            assertEquals(1, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void retryStopsAtConfiguredAttemptLimit() throws Exception {
        // 对端持续失败且不指定 pushback，观察策略中的最大尝试次数。
        String pushback = "none";

        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                Metadata trailers = new Metadata();
                if (!StringUtils.equals(pushback, "none")) {
                    trailers.put(PUSHBACK, pushback);
                }
                call.close(Status.UNAVAILABLE.withDescription(pushback), trailers);
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message limitFailureRequest = Message.of("limit", 0);
            Object[] limitFailureArguments = { limitFailureRequest };
            MetFuture limitFailure = caller.asyncInvoke(info, "echo", parameterTypes, limitFailureArguments);
            this.failure(limitFailure, 14);
            int expected = 3;
            assertEquals(pushback, expected, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void negativePushbackStopsRetry() throws Exception {
        // 对端用负数 pushback 明确禁止后续重试。
        String pushback = "-1";

        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                Metadata trailers = new Metadata();
                if (!StringUtils.equals(pushback, "none")) {
                    trailers.put(PUSHBACK, pushback);
                }
                call.close(Status.UNAVAILABLE.withDescription(pushback), trailers);
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message limitFailureRequest = Message.of("limit", 0);
            Object[] limitFailureArguments = { limitFailureRequest };
            MetFuture limitFailure = caller.asyncInvoke(info, "echo", parameterTypes, limitFailureArguments);
            this.failure(limitFailure, 14);
            int expected = 1;
            assertEquals(pushback, expected, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void invalidPushbackStopsRetry() throws Exception {
        // 对端返回非法 pushback，客户端应停止重试。
        String pushback = "invalid";

        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                Metadata trailers = new Metadata();
                if (!StringUtils.equals(pushback, "none")) {
                    trailers.put(PUSHBACK, pushback);
                }
                call.close(Status.UNAVAILABLE.withDescription(pushback), trailers);
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message limitFailureRequest = Message.of("limit", 0);
            Object[] limitFailureArguments = { limitFailureRequest };
            MetFuture limitFailure = caller.asyncInvoke(info, "echo", parameterTypes, limitFailureArguments);
            this.failure(limitFailure, 14);
            int expected = 1;
            assertEquals(pushback, expected, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void retryHonorsServerPushbackDelay() throws Exception {
        // 记录各次尝试到达时间，用对端 pushback 覆盖配置中的短退避。
        LinkedBlockingQueue<Long> arrivals = new LinkedBlockingQueue<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            arrivals.add(System.nanoTime());
            return this.requests(call, (values, ignored) -> {
                if (headers.get(PREVIOUS) == null) {
                    Metadata trailers = new Metadata();
                    trailers.put(PUSHBACK, "500");
                    call.close(Status.UNAVAILABLE, trailers);
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // pushback 优先于配置的短退避，不提前创建第二次尝试。
            Class<?>[] successTypes = { Message.class };
            Object[] successArguments = { Message.of("pushback", 0) };
            MetFuture success = caller.asyncInvoke(info, "echo", successTypes, successArguments);
            Long first = arrivals.poll(3, TimeUnit.SECONDS);
            Long second = arrivals.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(second - first);
            assertTrue(elapsedMillis >= 450);
            Message result = (Message) success.getData(3, TimeUnit.SECONDS);
            assertEquals("pushback", result.getValue());
        }
    }

    @Test(timeout = 20000)
    public void cancellationDuringPushbackPreventsAnotherAttempt() throws Exception {
        // 对端首次失败后要求等待，给调用方留出取消整个 RPC 的窗口。
        LinkedBlockingQueue<Long> arrivals = new LinkedBlockingQueue<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            arrivals.add(System.nanoTime());
            return this.requests(call, (values, ignored) -> {
                if (headers.get(PREVIOUS) == null) {
                    Metadata trailers = new Metadata();
                    trailers.put(PUSHBACK, "500");
                    call.close(Status.UNAVAILABLE, trailers);
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // Caller 的取消终止整个调用，不能只取消当前物理尝试。
            Class<?>[] cancelledTypes = { Message.class };
            Object[] cancelledArguments = { Message.of("cancel", 0) };
            MetFuture cancelled = caller.asyncInvoke(info, "echo", cancelledTypes, cancelledArguments);
            assertNotNull(arrivals.poll(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel());
            assertNull(arrivals.poll(700, TimeUnit.MILLISECONDS));
        }
    }

    @Test(timeout = 20000)
    public void deadlineIncludesRetryBackoff() throws Exception {
        // 重试等待长于调用期限，观察超时是否同时取消尚未开始的尝试。
        LinkedBlockingQueue<Long> arrivals = new LinkedBlockingQueue<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            arrivals.add(System.nanoTime());
            return this.requests(call, (values, ignored) -> {
                if (headers.get(PREVIOUS) == null) {
                    Metadata trailers = new Metadata();
                    trailers.put(PUSHBACK, "500");
                    call.close(Status.UNAVAILABLE, trailers);
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 200);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            Class<?>[] expiredTypes = { Message.class };
            Object[] expiredArguments = { Message.of("timeout", 0) };
            MetFuture expired = caller.asyncInvoke(info, "echo", expiredTypes, expiredArguments);
            assertNotNull(arrivals.poll(3, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> expired.getData(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MetTimeoutException);
            assertNull(arrivals.poll(700, TimeUnit.MILLISECONDS));
        }
    }

    @Test(timeout = 20000)
    public void throttleIsSharedByTargetAndSuccessfulCallsRestoreTokens() throws Exception {
        // 同一目标共用重试令牌，失败消耗令牌而成功响应补充令牌。
        String throttle = "\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1},";
        String replacement = "{" + throttle + "\"methodConfig\"";
        String configuration = RETRY.replace("{\"methodConfig\"", replacement);
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> {
                String requestValue = values.get(0).getValue();
                if (StringUtils.equals(requestValue, "success")) {
                    this.reply(call, values);
                } else {
                    call.close(Status.UNAVAILABLE, new Metadata());
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(configuration, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message firstFailureRequest = Message.of("fail", 0);
            Object[] firstFailureArguments = { firstFailureRequest };
            MetFuture firstFailure = caller.asyncInvoke(info, "echo", parameterTypes, firstFailureArguments);
            this.failure(firstFailure, 14);
            assertEquals(2, attempts.get());
            Message nextFailureRequest = Message.of("fail-again", 0);
            Object[] nextFailureArguments = { nextFailureRequest };
            MetFuture nextFailure = caller.asyncInvoke(info, "echo", parameterTypes, nextFailureArguments);
            this.failure(nextFailure, 14);
            assertEquals(3, attempts.get());

            // 正常响应的 grpc-status 在 trailers，成功后必须恢复令牌。
            for (int index = 0; index < 3; index++) {
                Message result = remote.echo(Message.of("success", index));
                assertEquals("success", result.getValue());
            }
            Message recoveredFailureRequest = Message.of("recovered", 0);
            Object[] recoveredFailureArguments = { recoveredFailureRequest };
            MetFuture recoveredFailure = caller.asyncInvoke(info, "echo", parameterTypes, recoveredFailureArguments);
            this.failure(recoveredFailure, 14);
            assertEquals(8, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void hedgingWinnerCancelsThePendingAttempt() throws Exception {
        // 第一份物理调用保持等待，第二份调用成功，并观察第一份是否取消。
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            int number = attempts.incrementAndGet();
            call.request(1);
            return new ServerCall.Listener<>() {
                private Message request;

                public void onMessage(Message message) {
                    this.request = message;
                }

                public void onHalfClose() {
                    if (number == 2) {
                        reply(call, List.of(this.request));
                    }
                }

                public void onCancel() {
                    if (number == 1) {
                        cancelled.countDown();
                    }
                }
            };
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(HEDGE, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 等待第二次尝试获胜，并检查首个尝试的取消通知。
            Message result = remote.echo(Message.of("hedge", 0));
            assertEquals("hedge", result.getValue());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void nonfatalHedgeSkipsItsConfiguredDelay() throws Exception {
        // 对端先返回非致命错误，下一次对冲不应继续等待原定三秒。
        String delayed = HEDGE.replace("0.1s", "3s");
        List<Long> arrived = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            arrived.add(System.nanoTime());
            return this.requests(call, (values, ignored) -> {
                String requestValue = values.get(0).getValue();
                if (StringUtils.equals(requestValue, "fatal")) {
                    call.close(Status.PERMISSION_DENIED, new Metadata());
                } else if (headers.get(PREVIOUS) == null) {
                    call.close(Status.UNAVAILABLE, new Metadata());
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(delayed, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 第二次尝试应立即接替，而非继续等待完整对冲延迟。
            Message result = remote.echo(Message.of("shortcut", 0));
            assertEquals("shortcut", result.getValue());
            assertEquals(2, arrived.size());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(arrived.get(1) - arrived.get(0));
            assertTrue("Hedge did not skip its delay: " + elapsed, elapsed < 2000);
        }
    }

    @Test(timeout = 20000)
    public void fatalStatusEndsHedgingAfterFirstAttempt() throws Exception {
        // 对端返回致命错误，验证尚未执行的对冲不会继续发起。
        String delayed = HEDGE.replace("0.1s", "3s");
        List<Long> arrived = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            arrived.add(System.nanoTime());
            return this.requests(call, (values, ignored) -> {
                String requestValue = values.get(0).getValue();
                if (StringUtils.equals(requestValue, "fatal")) {
                    call.close(Status.PERMISSION_DENIED, new Metadata());
                } else if (headers.get(PREVIOUS) == null) {
                    call.close(Status.UNAVAILABLE, new Metadata());
                } else {
                    this.reply(call, values);
                }
            });
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(delayed, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message fatalFailureRequest = Message.of("fatal", 0);
            Object[] fatalFailureArguments = { fatalFailureRequest };
            MetFuture fatalFailure = caller.asyncInvoke(info, "echo", parameterTypes, fatalFailureArguments);
            this.failure(fatalFailure, 7);
            assertEquals(1, arrived.size());
        }
    }

    @Test(timeout = 20000)
    public void exhaustedSharedThrottleFinishesAnAlreadyScheduledRetry() throws Exception {
        // 先安排一个待重试调用，再用另一个调用消耗同一目标的剩余令牌。
        String throttle = "\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1},";
        String configuration = RETRY.replace("{\"methodConfig\"", "{" + throttle + "\"methodConfig\"");
        LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> this.requests(call, (values, ignored) -> {
            Metadata trailers = new Metadata();
            trailers.put(PUSHBACK, "500");
            call.close(Status.UNAVAILABLE, trailers);
            requests.add(values.get(0).getValue());
        });
        MetConfiguration clientConfiguration = this.clientConfiguration(configuration, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 第一个请求进入 pushback 等待，第二个请求消耗剩余令牌。
            Class<?>[] firstTypes = { Message.class };
            Object[] firstArguments = { Message.of("waiting", 0) };
            MetFuture first = caller.asyncInvoke(info, "echo", firstTypes, firstArguments);
            assertEquals("waiting", requests.poll(3, TimeUnit.SECONDS));
            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message drainingFailureRequest = Message.of("drain tokens", 0);
            Object[] drainingFailureArguments = { drainingFailureRequest };
            MetFuture drainingFailure = caller.asyncInvoke(info, "echo", parameterTypes, drainingFailureArguments);
            this.failure(drainingFailure, 14);
            assertEquals("drain tokens", requests.poll(3, TimeUnit.SECONDS));

            // 已安排的重试也要以失败结束，不能再次向对端发送请求。
            this.failure(first, 14);
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test(timeout = 20000)
    public void serverStreamRetryReplaysItsSingleRequest() throws Exception {
        // 记录服务端流每次收到的请求，第一次失败后允许重试成功。
        List<String> received = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> this.requests(call, (values, ignored) -> {
            String method = call.getMethodDescriptor().getBareMethodName();
            received.add(method + ":" + values.size());
            if (headers.get(PREVIOUS) == null) {
                call.close(Status.UNAVAILABLE, new Metadata());
            } else if (StringUtils.equals(method, "collect")) {
                this.reply(call, List.of(Message.of("sum", values.size())));
            } else {
                this.reply(call, values);
            }
        });
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 服务端流虽然只有一个请求，也走可重放的增量正文。
            List<Message> list = new ArrayList<>();
            Flow.Publisher<Message> output = remote.list(Message.of("list", 1));
            var completion = MetCaller.consume(output, list::add);
            completion.get(5, TimeUnit.SECONDS);
            assertEquals(1, list.size());
            assertEquals(List.of("list:1", "list:1"), received);
        }
    }

    @Test(timeout = 20000)
    public void clientStreamRetrySubscribesToItsSourceOnlyOnce() throws Exception {
        // 记录请求流重试的数据数量，确认重放不会重新订阅业务数据源。
        List<String> received = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> this.requests(call, (values, ignored) -> {
            String method = call.getMethodDescriptor().getBareMethodName();
            received.add(method + ":" + values.size());
            if (headers.get(PREVIOUS) == null) {
                call.close(Status.UNAVAILABLE, new Metadata());
            } else if (StringUtils.equals(method, "collect")) {
                this.reply(call, List.of(Message.of("sum", values.size())));
            } else {
                this.reply(call, values);
            }
        });
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 统计业务 Publisher 的订阅次数，区分重放与重新采集数据。
            AtomicInteger subscriptions = new AtomicInteger();
            List<Message> values = List.of(Message.of("a", 1), Message.of("b", 2));
            Flow.Publisher<Message> input = subscriber -> {
                subscriptions.incrementAndGet();
                MetCaller.fromIterable(values).subscribe(subscriber);
            };
            Message result = remote.collect(input);
            assertEquals(2, result.getCount());
            assertEquals(1, subscriptions.get());
            assertEquals(List.of("collect:2", "collect:2"), received);
        }
    }

    @Test(timeout = 20000)
    public void bidirectionalRetrySubscribesToItsSourceOnlyOnce() throws Exception {
        // 记录双向流重试收到的数据，确认输入只订阅一次且回复不重复。
        List<String> received = new CopyOnWriteArrayList<>();
        ServerCallHandler<Message, Message> handler = (call, headers) -> this.requests(call, (values, ignored) -> {
            String method = call.getMethodDescriptor().getBareMethodName();
            received.add(method + ":" + values.size());
            if (headers.get(PREVIOUS) == null) {
                call.close(Status.UNAVAILABLE, new Metadata());
            } else if (StringUtils.equals(method, "collect")) {
                this.reply(call, List.of(Message.of("sum", values.size())));
            } else {
                this.reply(call, values);
            }
        });
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 262144, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            // 统计业务 Publisher 的订阅次数，区分重放与重新采集数据。
            AtomicInteger subscriptions = new AtomicInteger();
            List<Message> values = List.of(Message.of("a", 1), Message.of("b", 2));
            Flow.Publisher<Message> input = subscriber -> {
                subscriptions.incrementAndGet();
                MetCaller.fromIterable(values).subscribe(subscriber);
            };
            List<Message> replies = new ArrayList<>();
            Flow.Publisher<Message> output = remote.exchange(input);
            var completion = MetCaller.consume(output, replies::add);
            completion.get(5, TimeUnit.SECONDS);
            assertEquals(2, replies.size());
            assertEquals(1, subscriptions.get());
            assertEquals(List.of("exchange:2", "exchange:2"), received);
        }
    }

    @Test(timeout = 20000)
    public void unaryReplayBudgetPreventsAnotherAttempt() throws Exception {
        // 将重放预算设为八字节，让普通请求无法进入重试缓冲区。
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> call.close(Status.UNAVAILABLE, new Metadata()));
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 8, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 从公开异步入口发起业务调用，再观察协议实际执行的尝试次数。
            Class<?>[] parameterTypes = { Message.class };
            Message largeFailureRequest = Message.of("large unary message", 0);
            Object[] largeFailureArguments = { largeFailureRequest };
            MetFuture largeFailure = caller.asyncInvoke(info, "echo", parameterTypes, largeFailureArguments);
            this.failure(largeFailure, 14);
            assertEquals(1, attempts.get());
        }
    }

    @Test(timeout = 20000)
    public void streamReplayBudgetPreventsAnotherAttempt() throws Exception {
        // 将重放预算设为八字节，让流式请求无法进入重试缓冲区。
        AtomicInteger attempts = new AtomicInteger();
        ServerCallHandler<Message, Message> handler = (call, headers) -> {
            attempts.incrementAndGet();
            return this.requests(call, (values, ignored) -> call.close(Status.UNAVAILABLE, new Metadata()));
        };
        MetConfiguration clientConfiguration = this.clientConfiguration(RETRY, 8, 5000);
        try (Peer peer = new Peer(handler); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动应用后订阅普通 Java 接口，所有调用经过公开客户端入口。
            client.start();
            var binding = client.publisher() //
                    .rsfService(PolicyService.class) //
                    .aliasName("grpc", "test.Policy");
            MetBindInfo<PolicyService> info = binding.serialize("Json").register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.server.getPort(), "default");
            var caller = client.getRsfClient(address);
            PolicyService remote = caller.getRemote(info);

            List<Message> values = List.of(Message.of("large stream message", 1));
            Throwable failure = assertThrows(Throwable.class, () -> {
                Flow.Publisher<Message> output = remote.exchange(MetCaller.fromIterable(values));
                var completion = MetCaller.consume(output, ignored -> fail("No successful response expected"));
                completion.get(3, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(14, status.getCode());
            assertEquals(1, attempts.get());
        }
    }

    private ServerCall.Listener<Message> requests(ServerCall<Message, Message> call, //
            BiConsumer<List<Message>, ServerCall<Message, Message>> complete) {
        // 对端先接收全部输入，只在客户端半关闭后执行该场景的响应逻辑。
        call.request(32);
        return new ServerCall.Listener<>() {
            private final List<Message> values = new ArrayList<>();

            public void onMessage(Message value) {
                this.values.add(value);
                call.request(1);
            }

            public void onHalfClose() {
                complete.accept(this.values, call);
            }
        };
    }

    private void reply(ServerCall<Message, Message> call, List<Message> values) {
        // 正常调用按头部、正文、结束 trailers 的顺序提交响应。
        call.sendHeaders(new Metadata());
        for (Message value : values) {
            call.sendMessage(value);
        }
        call.close(Status.OK, new Metadata());
    }

    private void failure(MetFuture future, int status) {
        // 有界等待公开调用结果，确认返回的是原始 gRPC 状态。
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.getData(5, TimeUnit.SECONDS));
        Throwable cause = failure.getCause();
        assertTrue(cause.toString(), cause instanceof GrpcStatusException);
        assertEquals(status, ((GrpcStatusException) cause).getCode());
    }

    private static final class Peer implements AutoCloseable {
        private final Server server;

        private Peer(ServerCallHandler<Message, Message> handler) throws Exception {
            // 为四种调用模式注册真实官方方法描述，响应策略由当前场景传入。
            MethodDescriptor.Marshaller<Message> marshaller = marshaller();
            ServerServiceDefinition.Builder service = ServerServiceDefinition.builder("test.Policy");
            String[] names = { "echo", "list", "collect", "exchange" };
            MethodDescriptor.MethodType[] types = { MethodDescriptor.MethodType.UNARY, //
                    MethodDescriptor.MethodType.SERVER_STREAMING, //
                    MethodDescriptor.MethodType.CLIENT_STREAMING, //
                    MethodDescriptor.MethodType.BIDI_STREAMING //
            };
            for (int index = 0; index < names.length; index++) {
                var method = MethodDescriptor.<Message, Message>newBuilder().setType(types[index]);
                method.setFullMethodName("test.Policy/" + names[index]);
                method.setRequestMarshaller(marshaller).setResponseMarshaller(marshaller);
                service.addMethod(method.build(), handler);
            }
            ServerServiceDefinition definition = service.build();
            Server server = NettyServerBuilder.forPort(0).addService(definition).build();
            this.server = server.start();
        }

        private static MethodDescriptor.Marshaller<Message> marshaller() {
            // 对端独立使用 Jackson 编解码普通 Java 消息。
            ObjectMapper mapper = new ObjectMapper();
            return new MethodDescriptor.Marshaller<>() {
                public InputStream stream(Message value) {
                    try {
                        byte[] bytes = mapper.writeValueAsBytes(value);
                        return new ByteArrayInputStream(bytes);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }

                public Message parse(InputStream input) {
                    try {
                        return mapper.readValue(input, Message.class);
                    } catch (Exception failure) {
                        throw new IllegalArgumentException(failure);
                    }
                }
            };
        }

        public void close() throws Exception {
            this.server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private MetConfiguration clientConfiguration(String policy, int buffer, int timeout) {
        // 将策略和重放预算作为协议挂载选项，客户端只使用公开 bootstrap。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(timeout);
        var mount = configuration.connector("http").option("httpVersion", 2).protocol("grpc");
        mount.option("streamBody", true) //
                .option("serviceConfig", policy) //
                .option("retryBufferSize", buffer);
        return configuration;
    }
}
