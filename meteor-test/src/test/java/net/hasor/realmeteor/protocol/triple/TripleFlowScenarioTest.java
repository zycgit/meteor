/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

/** wrapper、原生 Protobuf 与 ProtoJSON 使用同一组 Java Flow API。 */
public class TripleFlowScenarioTest {
    @Test(timeout = 15000)
    public void wrapperUnaryPreservesBusinessValues() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 同一个 Java DTO 可由当前编码表达，不改变服务 API。
            TripleMessage result = remote.echo(TripleMessage.of("Triple 编码", 6));
            assertEquals("Triple 编码", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void wrapperServerStreamKeepsMessageOrder() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 服务端按需求产生多条消息，在同一次 RPC 中逐条交付。
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("response", 4)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, responses.size());
            assertEquals("response", responses.get(3).getText());
            assertEquals(3, responses.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void wrapperClientStreamReturnsItsSummary() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 请求流半关闭以后，服务只返回一次汇总结果。
            List<TripleMessage> input = List.of(TripleMessage.of("a", 2), TripleMessage.of("b", 3));
            TripleMessage result = remote.collect(MetCaller.fromIterable(input));
            assertEquals("sum", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void wrapperBidirectionalStreamKeepsEachMessage() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 双向流保留每条消息边界，两侧不需要先缓存完整集合。
            List<TripleMessage> input = List.of(TripleMessage.of("first", 2), TripleMessage.of("second", 3));
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.chat(MetCaller.fromIterable(input)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(2, responses.get(0).getNumber());
            assertEquals("second", responses.get(1).getText());
            assertEquals(3, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufUnaryPreservesBusinessValues() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 同一个 Java DTO 可由当前编码表达，不改变服务 API。
            TripleMessage result = remote.echo(TripleMessage.of("Triple 编码", 6));
            assertEquals("Triple 编码", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufServerStreamKeepsMessageOrder() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 服务端按需求产生多条消息，在同一次 RPC 中逐条交付。
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("response", 4)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, responses.size());
            assertEquals("response", responses.get(3).getText());
            assertEquals(3, responses.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufClientStreamReturnsItsSummary() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 请求流半关闭以后，服务只返回一次汇总结果。
            List<TripleMessage> input = List.of(TripleMessage.of("a", 2), TripleMessage.of("b", 3));
            TripleMessage result = remote.collect(MetCaller.fromIterable(input));
            assertEquals("sum", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufBidirectionalStreamKeepsEachMessage() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 双向流保留每条消息边界，两侧不需要先缓存完整集合。
            List<TripleMessage> input = List.of(TripleMessage.of("first", 2), TripleMessage.of("second", 3));
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.chat(MetCaller.fromIterable(input)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(2, responses.get(0).getNumber());
            assertEquals("second", responses.get(1).getText());
            assertEquals(3, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protoJsonUnaryPreservesBusinessValues() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 同一个 Java DTO 可由当前编码表达，不改变服务 API。
            TripleMessage result = remote.echo(TripleMessage.of("Triple 编码", 6));
            assertEquals("Triple 编码", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protoJsonServerStreamKeepsMessageOrder() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 服务端按需求产生多条消息，在同一次 RPC 中逐条交付。
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("response", 4)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, responses.size());
            assertEquals("response", responses.get(3).getText());
            assertEquals(3, responses.get(3).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protoJsonClientStreamReturnsItsSummary() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 请求流半关闭以后，服务只返回一次汇总结果。
            List<TripleMessage> input = List.of(TripleMessage.of("a", 2), TripleMessage.of("b", 3));
            TripleMessage result = remote.collect(MetCaller.fromIterable(input));
            assertEquals("sum", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protoJsonBidirectionalStreamKeepsEachMessage() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource).register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 双向流保留每条消息边界，两侧不需要先缓存完整集合。
            List<TripleMessage> input = List.of(TripleMessage.of("first", 2), TripleMessage.of("second", 3));
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.chat(MetCaller.fromIterable(input)), responses::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(2, responses.get(0).getNumber());
            assertEquals("second", responses.get(1).getText());
            assertEquals(3, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void emptyServerStreamCompletesWithoutSyntheticMessages() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 空流只携带完成终态，不能伪造一条空的业务消息。
            Flow.Publisher<TripleMessage> stream = remote.count(TripleMessage.of("empty", 0));
            MetCaller.consume(stream, message -> fail("No message was expected")) //
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void emptyClientStreamReturnsZero() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 空请求流仍是有效输入，结束后返回一次零值汇总。
            TripleMessage result = remote.collect(MetCaller.fromIterable(List.of()));
            assertEquals("sum", result.getText());
            assertEquals(0, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void earlyClientStreamResponseCancelsInputAndPreservesFollowingCalls() throws Exception {
        // 服务端不消费完整输入就返回，客户端源保持开放以观察反向取消。
        CountDownLatch cancelled = new CountDownLatch(1);
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl() {
            @Override
            public TripleMessage collect(Flow.Publisher<TripleMessage> requests) {
                return TripleMessage.of("early response", 7);
            }
        };
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 输入源最多发送第一条消息，并且不自行结束，结束责任交给协议取消。
            Flow.Publisher<TripleMessage> requests = subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(TripleMessage.of("unfinished input", 1));
                        }
                    }

                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            };
            TripleMessage result = remote.collect(requests);
            assertEquals("early response", result.getText());
            assertEquals(7, result.getNumber());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));

            // 早结束只终止当前 HTTP stream，后续普通调用仍走同一客户端上下文。
            TripleMessage following = remote.echo(TripleMessage.of("following", 9));
            assertEquals("following", following.getText());
            assertEquals(10, following.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void earlyClientStreamFailureCancelsInputAndPreservesFollowingCalls() throws Exception {
        // 服务端尚未消费完输入就失败，错误终态仍必须释放客户端的请求源。
        CountDownLatch cancelled = new CountDownLatch(1);
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl() {
            @Override
            public TripleMessage collect(Flow.Publisher<TripleMessage> requests) {
                throw new IllegalArgumentException("early stream failure");
            }
        };
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            var caller = client.getRsfClient(server.getListenAddress("triple"));

            // 输入源发出第一条后保持打开，只有对端终态才能结束这次发送。
            Flow.Publisher<TripleMessage> requests = subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(TripleMessage.of("unfinished input", 1));
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
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals("early stream failure", status.getDescription());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));

            // 同一 HTTP/2 连接上的下一次调用仍有独立的正常结果。
            TripleFlowService remote = caller.getRemote(service);
            TripleMessage following = remote.echo(TripleMessage.of("following", 9));
            assertEquals("following", following.getText());
            assertEquals(10, following.getNumber());
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true).option("httpMode", "grpc");
        return configuration;
    }
}
