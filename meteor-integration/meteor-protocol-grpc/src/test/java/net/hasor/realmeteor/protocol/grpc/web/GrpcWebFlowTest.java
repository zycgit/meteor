/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebFlowTest {
    @Test(timeout = 20000)
    public void binaryJsonServerStreamOverHttp1() throws Exception {
        // 单个 HTTP/1 请求逐条接收 Json 响应，结束状态位于 gRPC-Web 正文尾帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .register();

            // 通过 JDK Flow 消费同一次远程调用的多条结果。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("中文 stream", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            for (int index = 0; index < values.size(); index++) {
                assertEquals(index, values.get(index).getCount());
                assertEquals("中文 stream", values.get(index).getValue());
            }
        }
    }

    @Test(timeout = 20000)
    public void textProtobufServerStreamOverHttp1() throws Exception {
        // 单个 HTTP/1 请求逐条接收 Protobuf 响应，结束状态位于 gRPC-Web 正文尾帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过 JDK Flow 消费同一次远程调用的多条结果。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("中文 stream", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            for (int index = 0; index < values.size(); index++) {
                assertEquals(index, values.get(index).getCount());
                assertEquals("中文 stream", values.get(index).getValue());
            }
        }
    }

    @Test(timeout = 20000)
    public void binaryProtobufServerStreamOverHttp2() throws Exception {
        // 单个 HTTP/2 请求逐条接收 Protobuf 响应，结束状态位于 gRPC-Web 正文尾帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过 JDK Flow 消费同一次远程调用的多条结果。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("中文 stream", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            for (int index = 0; index < values.size(); index++) {
                assertEquals(index, values.get(index).getCount());
                assertEquals("中文 stream", values.get(index).getValue());
            }
        }
    }

    @Test(timeout = 20000)
    public void textJsonServerStreamOverHttp2() throws Exception {
        // 单个 HTTP/2 请求逐条接收 Json 响应，结束状态位于 gRPC-Web 正文尾帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .register();

            // 通过 JDK Flow 消费同一次远程调用的多条结果。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("中文 stream", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            for (int index = 0; index < values.size(); index++) {
                assertEquals(index, values.get(index).getCount());
                assertEquals("中文 stream", values.get(index).getValue());
            }
        }
    }

    @Test(timeout = 20000)
    public void emptyTextStreamCompletesWithOnlyTheTrailerFrame() throws Exception {
        // 空响应流只有状态尾帧，Base64 解码后不应构造一条空业务消息。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 完成信号只能来自解析出的成功状态，不能把 HTTP EOF 当作成功。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            MetCaller.consume(remote.list(Message.of("empty", 0)), value -> {
                fail("An empty stream must not contain a message");
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void textStreamReportsAnErrorAfterAValidMessage() throws Exception {
        // 业务已返回一条消息后失败，协议必须保留数据和最终非零状态。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 终态异常必须携带尾帧的状态，不能覆盖已经交付的首条消息。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            List<Message> values = new ArrayList<>();
            Future<Void> completion = MetCaller.consume(remote.broken(Message.of("first", 1)), values::add);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                completion.get(5, TimeUnit.SECONDS);
            });
            assertEquals(1, values.size());
            assertEquals("first", values.get(0).getValue());
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(9, status.getCode());
            assertEquals("late failure", status.getDescription());
        }
    }

    @Test(timeout = 20000)
    public void demandControlsDeliveryAcrossHttp1BodyChunks() throws Exception {
        // HTTP/1 增量正文也必须服从 Flow 的订阅需求，不能一次性全部交付。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 保存订阅句柄，但先不申请消息；大消息使响应跨多个 HTTP 正文块。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            LinkedBlockingQueue<Message> values = new LinkedBlockingQueue<>();
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            CountDownLatch subscribed = new CountDownLatch(1);
            BasicFuture<Void> finished = new BasicFuture<>();
            remote.list(Message.of("x".repeat(100000), 4)).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    subscribed.countDown();
                }

                @Override
                public void onNext(Message value) {
                    values.add(value);
                }

                @Override
                public void onError(Throwable failure) {
                    finished.failed(failure);
                }

                @Override
                public void onComplete() {
                    finished.completed(null);
                }
            });
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertNull(values.poll(100, TimeUnit.MILLISECONDS));

            // 首次只交付一条，后续结果等待新的需求量。
            subscription.get().request(1);
            Message first = values.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(100000, first.getValue().length());
            assertEquals(0, first.getCount());
            assertNull(values.poll(100, TimeUnit.MILLISECONDS));

            // 补足需求后按顺序交付剩余数据，最后解析状态尾帧并结束。
            subscription.get().request(3);
            for (int index = 1; index < 4; index++) {
                Message value = values.poll(5, TimeUnit.SECONDS);
                assertNotNull(value);
                assertEquals(index, value.getCount());
            }
            finished.get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void http1CancellationStopsLocallyAndTheDeadlineReleasesTheRemotePublisher() throws Exception {
        // 冻结的 HTTP/1 通道把 FIN 视为输入半关闭，远端响应流由显式期限最终回收。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true).setDefaultTimeout(1000);
        CountDownLatch producing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebServiceImpl implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message query) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                        }

                        @Override
                        public void cancel() {
                            cancelled.countDown();
                        }
                    });
                    producing.countDown();
                };
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, implementation) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 本地取消立即结束等待并停止交付；HTTP/1 不承诺立即向远端传播取消。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Future<Void> completion = MetCaller.consume(remote.list(Message.of("waiting", 1)), value -> {
                fail("The controlled publisher must not emit messages");
            });
            assertTrue(producing.await(5, TimeUnit.SECONDS));
            assertTrue(completion.cancel());
            assertTrue(completion.isCancelled());

            // 保留调用期限，远端即使尚未观察到写失败，也必须释放未完成的业务源。
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 20000)
    public void deadlineReleasesTheRemotePublisherWithoutAResponse() throws Exception {
        // 使用不产出数据的业务 Publisher，观察请求生命周期的远端释放。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, false).setDefaultTimeout(800);
        CountDownLatch producing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebServiceImpl implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message query) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                        }

                        @Override
                        public void cancel() {
                            cancelled.countDown();
                        }
                    });
                    producing.countDown();
                };
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, implementation) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 等待远端确实订阅业务源，再验证本次取消或期限触发的关闭。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Future<Void> completion = MetCaller.consume(remote.list(Message.of("waiting", 1)), value -> {
                fail("The controlled publisher must not emit messages");
            });
            assertTrue(producing.await(5, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                completion.get(3, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            if (status != null) {
                assertEquals(4, status.getCode());
            } else {
                assertTrue("Expected deadline failure, received: " + failure, //
                        failure.getCause() instanceof MetTimeoutException);
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        return configuration;
    }

    private MetConfiguration outgoing(int version, boolean text) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").option("httpVersion", version).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true).option("webText", text);
        return configuration;
    }
}
