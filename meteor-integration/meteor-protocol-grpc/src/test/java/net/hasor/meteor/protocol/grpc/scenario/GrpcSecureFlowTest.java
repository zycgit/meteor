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
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.meteor.protocol.grpc.scenario.example.MessageService;
import net.hasor.meteor.protocol.grpc.scenario.example.Messages;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

/** TLS 上的编码与流模式均通过独立方法显示在测试报告中。 */
public class GrpcSecureFlowTest {

    @Test(timeout = 20000)
    public void jsonTlsServerStream() throws Exception {
        // 准备 Json 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 读取服务端生成的三条响应，验证结束信号、内容与顺序。
            List<Message> responses = new ArrayList<>();
            var stream = remote.list(Message.of("安全服务端流", 3));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("安全服务端流", responses.get(2).getValue());
            assertEquals(2, responses.get(2).getCount());
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsClientStream() throws Exception {
        // 准备 Json 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 客户端半关闭请求流后，服务端返回完整汇总。
            Message first = Message.of("first", 2);
            Message second = Message.of("second", 3);
            List<Message> requests = List.of(first, second);
            Message response = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsBidirectionalStream() throws Exception {
        // 准备 Json 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsServerStream() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 读取服务端生成的三条响应，验证结束信号、内容与顺序。
            List<Message> responses = new ArrayList<>();
            var stream = remote.list(Message.of("安全服务端流", 3));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("安全服务端流", responses.get(2).getValue());
            assertEquals(2, responses.get(2).getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsClientStream() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 客户端半关闭请求流后，服务端返回完整汇总。
            Message first = Message.of("first", 2);
            Message second = Message.of("second", 3);
            List<Message> requests = List.of(first, second);
            Message response = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsBidirectionalStream() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaTlsServerStream() throws Exception {
        // 准备 Java 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 读取服务端生成的三条响应，验证结束信号、内容与顺序。
            List<Message> responses = new ArrayList<>();
            var stream = remote.list(Message.of("安全服务端流", 3));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("安全服务端流", responses.get(2).getValue());
            assertEquals(2, responses.get(2).getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaTlsClientStream() throws Exception {
        // 准备 Java 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 客户端半关闭请求流后，服务端返回完整汇总。
            Message first = Message.of("first", 2);
            Message second = Message.of("second", 3);
            List<Message> requests = List.of(first, second);
            Message response = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaTlsBidirectionalStream() throws Exception {
        // 准备 Java 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Java") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianTlsServerStream() throws Exception {
        // 准备 Hessian 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 读取服务端生成的三条响应，验证结束信号、内容与顺序。
            List<Message> responses = new ArrayList<>();
            var stream = remote.list(Message.of("安全服务端流", 3));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("安全服务端流", responses.get(2).getValue());
            assertEquals(2, responses.get(2).getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianTlsClientStream() throws Exception {
        // 准备 Hessian 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 客户端半关闭请求流后，服务端返回完整汇总。
            Message first = Message.of("first", 2);
            Message second = Message.of("second", 3);
            List<Message> requests = List.of(first, second);
            Message response = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianTlsBidirectionalStream() throws Exception {
        // 准备 Hessian 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hessian") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseTlsServerStream() throws Exception {
        // 准备 Hprose 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 读取服务端生成的三条响应，验证结束信号、内容与顺序。
            List<Message> responses = new ArrayList<>();
            var stream = remote.list(Message.of("安全服务端流", 3));
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("安全服务端流", responses.get(2).getValue());
            assertEquals(2, responses.get(2).getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseTlsClientStream() throws Exception {
        // 准备 Hprose 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 客户端半关闭请求流后，服务端返回完整汇总。
            Message first = Message.of("first", 2);
            Message second = Message.of("second", 3);
            List<Message> requests = List.of(first, second);
            Message response = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", response.getValue());
            assertEquals(5, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseTlsBidirectionalStream() throws Exception {
        // 准备 Hprose 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Hprose") //
                    .register();
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufMutualTlsBidirectionalStream() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与双向认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, true);
        MetConfiguration outgoing = this.configuration(false, true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 双向流逐条回传请求，并在请求半关闭后正常结束。
            List<Message> requests = List.of(Message.of("第一条", 2), Message.of("第二条", 3));
            var stream = remote.exchange(MetCaller.fromIterable(requests));
            List<Message> responses = new ArrayList<>();
            MetCaller.consume(stream, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("第一条", responses.get(0).getValue());
            assertEquals(2, responses.get(0).getCount());
            assertEquals("第二条", responses.get(1).getValue());
            assertEquals(3, responses.get(1).getCount());
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsCancellation() throws Exception {
        // 准备 Json 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 确认远端消息源已订阅，再取消并等待服务端释放生产源。
            Probe probe = new Probe();
            remote.never(Message.of("cancel", 0)).subscribe(probe);
            assertTrue(probe.subscribed.await(3, TimeUnit.SECONDS));
            assertTrue(implementation.getProducing().await(3, TimeUnit.SECONDS));
            probe.subscription.cancel();
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));

            // 同一 TLS 连接随后仍能完成独立流，证明取消没有污染连接。
            List<Message> responses = new ArrayList<>();
            var next = remote.list(Message.of("after cancel", 1));
            MetCaller.consume(next, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(1, responses.size());
            assertEquals("after cancel", responses.get(0).getValue());
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsCancellation() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 确认远端消息源已订阅，再取消并等待服务端释放生产源。
            Probe probe = new Probe();
            remote.never(Message.of("cancel", 0)).subscribe(probe);
            assertTrue(probe.subscribed.await(3, TimeUnit.SECONDS));
            assertTrue(implementation.getProducing().await(3, TimeUnit.SECONDS));
            probe.subscription.cancel();
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));

            // 同一 TLS 连接随后仍能完成独立流，证明取消没有污染连接。
            List<Message> responses = new ArrayList<>();
            var next = remote.list(Message.of("after cancel", 1));
            MetCaller.consume(next, responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(1, responses.size());
            assertEquals("after cancel", responses.get(0).getValue());
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsSourceFailure() throws Exception {
        // 准备 Json 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 消费响应流后，业务错误必须以非成功 gRPC 状态交付给调用方。
            Probe probe = new Probe();
            remote.broken(Message.of("fail", 0)).subscribe(probe);
            assertTrue(probe.subscribed.await(3, TimeUnit.SECONDS));
            probe.subscription.request(1);
            Exception failure = assertThrows(Exception.class, () -> probe.finished.get(5, TimeUnit.SECONDS));
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(13, status.getCode());
            assertTrue(status.getDescription().contains("source failure"));
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsSourceFailure() throws Exception {
        // 准备 Protobuf 编码、h2 ALPN 与服务端认证 TLS 流式连接。
        MetConfiguration incoming = this.configuration(true, false);
        MetConfiguration outgoing = this.configuration(false, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        MessageService implementation = new MessageService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一流式服务，确保走实际网络地址。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
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
            Messages remote = client.getRsfClient(server.bindAddress("grpc")).getRemote(info);

            // 消费响应流后，业务错误必须以非成功 gRPC 状态交付给调用方。
            Probe probe = new Probe();
            remote.broken(Message.of("fail", 0)).subscribe(probe);
            assertTrue(probe.subscribed.await(3, TimeUnit.SECONDS));
            probe.subscription.request(1);
            Exception failure = assertThrows(Exception.class, () -> probe.finished.get(5, TimeUnit.SECONDS));
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(13, status.getCode());
            assertTrue(status.getDescription().contains("source failure"));
        }
    }

    private MetConfiguration configuration(boolean server, boolean mutual) {
        // 复用无资源配置；双向认证同时要求服务端验客户端证书、客户端装载独立身份。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        http.option("tlsEnabled", true);
        http.option("httpScheme", "https");
        http.option("tlsAlpn", "h2");
        http.option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12");
        http.option("tlsTrustStorePassword", "meteor-test");
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        if (mutual) {
            http.option("tlsClientAuth", "REQUIRE");
        }
        if (server || mutual) {
            String identity = server ? "server" : "client";
            http.option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12");
            http.option("tlsKeyStorePassword", "meteor-test");
        }
        http.protocol("grpc").addressScheme("https").option("streamBody", true);
        return configuration;
    }

    private static final class Probe implements Flow.Subscriber<Message> {
        private final CountDownLatch    subscribed = new CountDownLatch(1);
        private final BasicFuture<Void> finished   = new BasicFuture<>();
        private       Flow.Subscription subscription;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscribed.countDown();
        }

        public void onNext(Message value) {
            fail("The cancelled or failed source must not emit a message");
        }

        public void onError(Throwable failure) {
            this.finished.failed(failure);
        }

        public void onComplete() {
            this.finished.completed(null);
        }
    }
}
