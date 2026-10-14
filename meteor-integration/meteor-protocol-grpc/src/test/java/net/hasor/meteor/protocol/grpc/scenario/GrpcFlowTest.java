/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.meteor.protocol.grpc.scenario.example.MessageService;
import net.hasor.meteor.protocol.grpc.scenario.example.Messages;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcFlowTest {

    @Test(timeout = 20000)
    public void protobufServerStreamReturnsEachMessage() throws Exception {
        // 配置 Protobuf 资源和双端 HTTP/2 增量正文。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));

        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 请求四条中文消息，等待服务端流结束后检查内容与顺序。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(new Message("中文", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals(3, values.get(3).getCount());
            assertEquals("中文", values.get(3).getValue());
        }
    }

    @Test(timeout = 20000)
    public void protobufClientStreamReturnsItsSummary() throws Exception {
        // 配置 Protobuf 客户端流，双方绑定相同的 proto 资源。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));

        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 将四条消息作为请求流发送，验证半关闭后得到的汇总。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                values.add(new Message("中文", index));
            }
            Message sum = remote.collect(MetCaller.fromIterable(values));
            assertEquals(6, sum.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufEmptyClientStreamReturnsZero() throws Exception {
        // 配置支持 Protobuf 的客户端流服务。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));

        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 发送空请求流，服务端仍应返回零值汇总。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Flow.Publisher<Message> emptyRequests = MetCaller.fromIterable(List.of());
            Message summary = remote.collect(emptyRequests);
            assertEquals(0, summary.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufBidirectionalStreamReturnsEachRequest() throws Exception {
        // 配置 Protobuf 双向流，双方通过同一 proto 描述消息。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));

        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 发送四条请求并收集响应，验证双向流逐条回传。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                values.add(new Message("中文", index));
            }
            Flow.Publisher<Message> requests = MetCaller.fromIterable(values);
            Flow.Publisher<Message> responses = remote.exchange(requests);
            List<Message> replies = new ArrayList<>();
            MetCaller.consume(responses, replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, replies.size());
            assertEquals(3, replies.get(3).getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufEmptyServerStreamCompletesWithoutMessages() throws Exception {
        // 配置 Protobuf 服务端流，准备请求零条消息。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));

        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 订阅空响应流；正常完成前不允许出现任何消息。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            MetCaller.consume(remote.list(new Message("empty", 0)), value -> fail()) //
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void serverStreamReturnsEachMessage() throws Exception {
        // 配置 JSON 服务端流和双端 HTTP/2 增量正文。
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 请求四条中文消息，等待服务端流结束后检查内容与顺序。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(new Message("中文", 4)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals(3, values.get(3).getCount());
            assertEquals("中文", values.get(3).getValue());
        }
    }

    @Test(timeout = 20000)
    public void clientStreamReturnsItsSummary() throws Exception {
        // 配置 JSON 客户端流服务。
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 将四条消息作为请求流发送，验证半关闭后得到的汇总。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                values.add(new Message("中文", index));
            }
            Message sum = remote.collect(MetCaller.fromIterable(values));
            assertEquals(6, sum.getCount());
        }
    }

    @Test(timeout = 20000)
    public void emptyClientStreamReturnsZero() throws Exception {
        // 配置 JSON 客户端流服务。
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 发送空请求流，服务端仍应返回零值汇总。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Flow.Publisher<Message> emptyRequests = MetCaller.fromIterable(List.of());
            Message summary = remote.collect(emptyRequests);
            assertEquals(0, summary.getCount());
        }
    }

    @Test(timeout = 20000)
    public void bidirectionalStreamReturnsEachRequest() throws Exception {
        // 配置 JSON 双向流服务。
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 发送四条请求并收集响应，验证双向流逐条回传。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            List<Message> values = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                values.add(new Message("中文", index));
            }
            Flow.Publisher<Message> requests = MetCaller.fromIterable(values);
            Flow.Publisher<Message> responses = remote.exchange(requests);
            List<Message> replies = new ArrayList<>();
            MetCaller.consume(responses, replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, replies.size());
            assertEquals(3, replies.get(3).getCount());
        }
    }

    @Test(timeout = 20000)
    public void emptyServerStreamCompletesWithoutMessages() throws Exception {
        // 配置 JSON 服务端流，准备请求零条消息。
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 订阅空响应流；正常完成前不允许出现任何消息。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            MetCaller.consume(remote.list(new Message("empty", 0)), value -> fail()) //
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void demandControlsDeliveryAcrossHttp2Windows() throws Exception {
        // 配置可持续生成大消息的服务，验证需求量与 HTTP/2 窗口配合。
        try (MetContext server = this.configuration(true, 10000).buildContext(); //
             MetContext client = this.configuration(false, 10000).buildContext()) {
            MessageService implementation = new MessageService();
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 订阅跨窗口的大消息流；未请求消息前，不应向订阅者交付。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Flow.Publisher<Message> result = remote.list(new Message("x".repeat(200000), 20));
            Probe probe = new Probe();
            result.subscribe(probe);
            assertTrue(probe.subscribed.await(2, TimeUnit.SECONDS));
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(implementation.getProduced().get() < 20);

            // 首次只申请一条，交付后必须继续等待新的需求量。
            probe.subscription.request(1);
            Message first = probe.values.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(200000, first.getValue().length());
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));

            // 再申请剩余消息，跨多个窗口后仍须保持完整顺序并正常结束。
            probe.subscription.request(19);
            for (int index = 1; index < 20; index++) {
                Message next = probe.values.poll(3, TimeUnit.SECONDS);
                assertNotNull(next);
                assertEquals(index, next.getCount());
            }
            probe.finished.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void secondSubscriberIsRejected() throws Exception {
        // 配置服务端流，验证单个远程响应流只允许一个订阅者。
        try (MetContext server = this.configuration(true, 10000).buildContext(); //
             MetContext client = this.configuration(false, 10000).buildContext()) {
            MessageService implementation = new MessageService();
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 向同一响应流注册两个订阅者，第二次订阅必须失败。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Flow.Publisher<Message> result = remote.list(new Message("x".repeat(200000), 20));
            Probe first = new Probe();
            result.subscribe(first);
            Probe duplicate = new Probe();
            result.subscribe(duplicate);
            assertThrows(Exception.class, () -> duplicate.finished.get(2, TimeUnit.SECONDS));
            assertTrue(duplicate.finished.getCause() instanceof IllegalStateException);
            first.subscription.cancel();
        }
    }

    @Test(timeout = 15000)
    public void cancellationReleasesTheRemoteSource() throws Exception {
        // 准备可观察取消信号的消息源和两端流式配置。
        MessageService implementation = new MessageService();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 等待远端消息源开始执行，再取消订阅并确认源被释放。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Probe cancelled = new Probe();
            remote.never(new Message()).subscribe(cancelled);
            assertTrue(cancelled.subscribed.await(2, TimeUnit.SECONDS));
            assertTrue(implementation.getProducing().await(2, TimeUnit.SECONDS));
            cancelled.subscription.cancel();
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void sourceFailureIsDeliveredToSubscriber() throws Exception {
        // 准备会主动失败的远端消息源。
        MessageService implementation = new MessageService();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 请求一条消息，确认源异常通过订阅者终态交付。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Probe failure = new Probe();
            remote.broken(new Message()).subscribe(failure);
            failure.subscription.request(1);
            assertThrows(Exception.class, () -> failure.finished.get(3, TimeUnit.SECONDS));
            assertTrue(failure.finished.getCause().getMessage().contains("source failure"));
        }
    }

    @Test(timeout = 15000)
    public void invalidDemandDoesNotPoisonLaterCalls() throws Exception {
        // 准备服务端流，用非法需求量验证调用之间的隔离。
        MessageService implementation = new MessageService();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 请求零条消息，当前订阅应收到非法参数错误。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Probe invalid = new Probe();
            remote.list(new Message("bad demand", 2)).subscribe(invalid);
            invalid.subscription.request(0);
            assertThrows(Exception.class, () -> invalid.finished.get(3, TimeUnit.SECONDS));
            assertTrue(invalid.finished.getCause() instanceof IllegalArgumentException);

            // 错误只结束当前流，后续调用仍可正常交付消息。
            Flow.Publisher<Message> following = remote.list(new Message("after failure", 1));
            Future<Void> completed = MetCaller.consume(following, value -> {
                assertEquals("after failure", value.getValue());
            });
            completed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void deadlineFailsAStreamWithoutDemand() throws Exception {
        // 将双端调用期限设为 600 毫秒，准备永不产出消息的流。
        MessageService implementation = new MessageService();
        try (MetContext server = this.configuration(true, 600).buildContext(); //
             MetContext client = this.configuration(false, 600).buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 仅订阅而不 request，期限到达后仍应失败并取消远端源。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Probe timeout = new Probe();
            remote.never(new Message()).subscribe(timeout);
            assertThrows(Exception.class, () -> timeout.finished.get(3, TimeUnit.SECONDS));
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void contextCloseFailsAStreamWithoutDemand() throws Exception {
        // 准备未发出需求的响应流，验证上下文关闭时的清理。
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 订阅后关闭客户端，未完成的响应流必须收到失败通知。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Probe closing = new Probe();
            remote.never(new Message()).subscribe(closing);
            client.close();
            assertThrows(Exception.class, () -> closing.finished.get(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void blockedConsumerDoesNotPreventDeadlineFromReleasingTheRequestPermit() throws Exception {
        // 将客户端并发配额设为一，利用闩锁阻塞首个消息的处理。
        MetConfiguration configuration = this.configuration(false, 700);
        configuration.setMaximumRequest(1).setSendLimitPolicy(SendLimitPolicy.WaitSecond);
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = this.configuration(true, 3000).buildContext(); //
             MetContext client = configuration.buildContext()) {
            // 先启动两端容器，再通过公开 API 发布和订阅流式服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, new MessageService()) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();

            // 让消息消费停在回调中，等待该调用期限独立释放请求配额。
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
            Flow.Publisher<Message> messages = remote.list(new Message("x".repeat(200000), 20));
            Future<Void> blocked = MetCaller.consume(messages, value -> {
                processing.countDown();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(processing.await(3, TimeUnit.SECONDS));

            // 唯一配额被首个流持有，截止时间必须先释放它，后续 RPC 才能执行。
            Flow.Publisher<Message> following = remote.list(new Message("after deadline", 1));
            Future<Void> completed = MetCaller.consume(following, value -> {
                assertEquals("after deadline", value.getValue());
            });
            completed.get(3, TimeUnit.SECONDS);
            assertEquals(1, release.getCount());
            release.countDown();
            assertThrows(Exception.class, () -> blocked.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private MetConfiguration configuration(boolean server, int timeout) {
        // 客户端只挂载出站协议，服务端额外绑定随机端口。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(timeout);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").option("streamBody", true);
        return configuration;
    }

    private static final class Probe implements Flow.Subscriber<Message> {
        private final LinkedBlockingQueue<Message> values     = new LinkedBlockingQueue<>();
        private final CountDownLatch               subscribed = new CountDownLatch(1);
        private final BasicFuture<Void>            finished   = new BasicFuture<>();
        private       Flow.Subscription            subscription;

        public void onSubscribe(Flow.Subscription subscription) {
            // 先保存订阅句柄，再通知测试线程可以发出需求或取消。
            this.subscription = subscription;
            this.subscribed.countDown();
        }

        public void onNext(Message value) {
            this.values.add(value);
        }

        public void onError(Throwable failure) {
            this.finished.failed(failure);
        }

        public void onComplete() {
            this.finished.completed(null);
        }
    }
}
