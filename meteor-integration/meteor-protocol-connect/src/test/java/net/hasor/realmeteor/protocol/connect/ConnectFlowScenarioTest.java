/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectFlowScenarioTest {
    @Test(timeout = 15000)
    public void jsonServerStreamKeepsMessageOrder() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 一个请求返回四条消息，流完成后再检查消息顺序和内容。
            List<ConnectMessage> values = new ArrayList<>();
            Flow.Publisher<ConnectMessage> stream = remote.count(ConnectMessage.of("流式消息", 4));
            MetCaller.consume(stream, values::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals("流式消息", values.get(3).getText());
            assertEquals(3, values.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void jsonClientStreamReturnsItsSummary() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 多条输入在请求流半关闭后，返回一个累加结果。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("a", 2), ConnectMessage.of("b", 3));
            ConnectMessage summary = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", summary.getText());
            assertEquals(5, summary.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void jsonBidirectionalStreamKeepsEachRequest() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 双向流在同一次 RPC 中收发多条消息，保持每条消息独立。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("first", 2), ConnectMessage.of("second", 3));
            List<ConnectMessage> responses = new ArrayList<>();
            Flow.Publisher<ConnectMessage> stream = remote.chat(MetCaller.fromIterable(requests));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(2, responses.get(0).getNumber());
            assertEquals("second", responses.get(1).getText());
            assertEquals(3, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufServerStreamKeepsMessageOrder() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 一个请求返回四条消息，流完成后再检查消息顺序和内容。
            List<ConnectMessage> values = new ArrayList<>();
            Flow.Publisher<ConnectMessage> stream = remote.count(ConnectMessage.of("流式消息", 4));
            MetCaller.consume(stream, values::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals("流式消息", values.get(3).getText());
            assertEquals(3, values.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufClientStreamReturnsItsSummary() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 多条输入在请求流半关闭后，返回一个累加结果。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("a", 2), ConnectMessage.of("b", 3));
            ConnectMessage summary = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", summary.getText());
            assertEquals(5, summary.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufBidirectionalStreamKeepsEachRequest() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 双向流在同一次 RPC 中收发多条消息，保持每条消息独立。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("first", 2), ConnectMessage.of("second", 3));
            List<ConnectMessage> responses = new ArrayList<>();
            Flow.Publisher<ConnectMessage> stream = remote.chat(MetCaller.fromIterable(requests));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(2, responses.get(0).getNumber());
            assertEquals("second", responses.get(1).getText());
            assertEquals(3, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void emptyServerStreamCompletesWithoutMessages() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 空流只有结束信封，订阅者不能收到伪造的空业务消息。
            Flow.Publisher<ConnectMessage> stream = remote.count(ConnectMessage.of("empty", 0));
            MetCaller.consume(stream, message -> fail("No message was expected")) //
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void emptyClientStreamReturnsZero() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 请求流没有元素时仍正常完成，服务返回零值汇总。
            Flow.Publisher<ConnectMessage> requests = MetCaller.fromIterable(List.of());
            ConnectMessage summary = remote.collect(requests);
            assertEquals(0, summary.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void http1ServerStreamUsesIncrementalResponseBody() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 1);
        MetConfiguration outgoing = this.configuration(false, 1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 一个请求返回四条消息，流完成后再检查消息顺序和内容。
            List<ConnectMessage> values = new ArrayList<>();
            Flow.Publisher<ConnectMessage> stream = remote.count(ConnectMessage.of("流式消息", 4));
            MetCaller.consume(stream, values::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals("流式消息", values.get(3).getText());
            assertEquals(3, values.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void http1ClientStreamUsesIncrementalRequestBody() throws Exception {
        // 两端通过正式配置开启增量正文，消息直接映射为 Java 流元素。
        MetConfiguration incoming = this.configuration(true, 1);
        MetConfiguration outgoing = this.configuration(false, 1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成所有容器启动，再发布与订阅同一个流式服务。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 多条输入在请求流半关闭后，返回一个累加结果。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("a", 2), ConnectMessage.of("b", 3));
            ConnectMessage summary = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", summary.getText());
            assertEquals(5, summary.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void earlyClientStreamResponseCancelsInputAndPreservesFollowingCalls() throws Exception {
        // 服务端不消费完整输入就返回，客户端源保持开放以观察反向取消。
        CountDownLatch cancelled = new CountDownLatch(1);
        ConnectDemoServiceImpl implementation = new ConnectDemoServiceImpl() {
            @Override
            public ConnectMessage collect(Flow.Publisher<ConnectMessage> requests) {
                return ConnectMessage.of("early response", 7);
            }
        };
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, implementation) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 输入源最多发送第一条消息，并且不自行结束，结束责任交给协议取消。
            Flow.Publisher<ConnectMessage> requests = subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(ConnectMessage.of("unfinished input", 1));
                        }
                    }

                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            };
            ConnectMessage result = remote.collect(requests);
            assertEquals("early response", result.getText());
            assertEquals(7, result.getNumber());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));

            // 早结束只终止当前 HTTP stream，后续普通调用仍走同一客户端上下文。
            ConnectMessage following = remote.echo(ConnectMessage.of("following", 9));
            assertEquals("following", following.getText());
            assertEquals(10, following.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void earlyClientStreamFailureCancelsInputAndPreservesFollowingCalls() throws Exception {
        // 服务端尚未消费完输入就失败，错误终态仍必须释放客户端的请求源。
        CountDownLatch cancelled = new CountDownLatch(1);
        ConnectDemoServiceImpl implementation = new ConnectDemoServiceImpl() {
            @Override
            public ConnectMessage collect(Flow.Publisher<ConnectMessage> requests) {
                throw new IllegalArgumentException("early stream failure");
            }
        };
        MetConfiguration incoming = this.configuration(true, 2);
        MetConfiguration outgoing = this.configuration(false, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, implementation) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            var caller = client.getRsfClient(server.getListenAddress("connect"));

            // 输入源发出第一条后保持打开，只有对端终态才能结束这次发送。
            Flow.Publisher<ConnectMessage> requests = subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(ConnectMessage.of("unfinished input", 1));
                        }
                    }

                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            };
            Class<?>[] types = { Flow.Publisher.class };
            Object[] arguments = { requests };
            var pending = caller.asyncInvoke(service, "collect", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(3, TimeUnit.SECONDS);
            });
            ConnectStatusException status = ConnectStatusException.find(failure);
            assertNotNull(status);
            assertEquals("invalid_argument", status.getCode());
            assertEquals("early stream failure", status.getDescription());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));

            // 同一 HTTP/2 连接上的下一次调用仍有独立的正常结果。
            ConnectDemoService remote = caller.getRemote(service);
            ConnectMessage following = remote.echo(ConnectMessage.of("following", 9));
            assertEquals("following", following.getText());
            assertEquals(10, following.getNumber());
        }
    }

    private MetConfiguration configuration(boolean listener, int version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", version);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("connect").contextPath("/rpc").option("streamBody", true);
        return configuration;
    }
}
