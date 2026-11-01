/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.example.direct.ProtocolDescriptionExample;
import net.hasor.example.oncrpc.OncRpcOrderService;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.example.protocol.oncrpc.OncRpcSample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcException;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcIdlResource;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 正式发布入口上的契约校验、程序路由和共享监听。 */
@NxnRpc(name = "oncrpc", version = "2")
public class OncRpcPublicationScenarioTest {
    @TempDir
    public Path contracts;

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void publicOrderFieldIdsMatchTheExistingXdrMemberSequence() throws Exception {
        // 消费者的文件固定成员顺序，提供者直接复用公共 Order 的字段号生成 XDR 映射。
        Path contract = Files.createFile(this.contracts.resolve("order-fields.x"));
        Files.writeString(contract, """
                struct Order {
                    int id;
                    string description<>;
                    opaque attachment<>;
                };
                program ORDER_PROGRAM {
                    version ORDER_VERSION {
                        Order save(Order) = 1;
                        Order find(int) = 2;
                    } = 1;
                } = 0x31234569;
                """);
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            ProtocolDescriptionExample.publishOrdersByXdrFields(server, new OrderDemoServiceImpl());
            MetBindInfo<OncRpcOrderService> service = client.publisher().rsfService(OncRpcOrderService.class) //
                    .name(OncRpcOrderService.class.getName()).protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcIdlResource(contract.toUri().toURL())).register();

            // 文件不携带字段编号；真实收发验证自动映射采用 1、2、3 对应的成员顺序。
            OncRpcOrderService remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);
            Order order = new Order();
            order.setId(31);
            order.setDescription("common-field-order");
            order.setAttachment(new byte[] { 0, 1, -1 });
            Order result = remote.save(order);
            assertEquals(order.getId(), result.getId());
            assertEquals(order.getDescription(), result.getDescription());
            assertArrayEquals(order.getAttachment(), result.getAttachment());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void resourceProgramVersionIsIndependentOfTheMeteorServiceVersion() throws Exception {
        // 程序版本由协议资源选择，业务服务版本仍保留原有字符串语义。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            OncRpcServiceResource publishedResource = new OncRpcServiceResource(Echo.class);
            publishedResource.setVersion(2);
            MetBindInfo<Echo> published = server.publisher().rsfService(Echo.class, value -> "v2:" + value) //
                    .version("deployment-v1").bindResource("oncrpc", publishedResource).register();
            OncRpcServiceResource subscribedResource = new OncRpcServiceResource(Echo.class);
            subscribedResource.setVersion(2);
            MetBindInfo<Echo> subscribed = client.publisher().rsfService(Echo.class) //
                    .version("deployment-v1").bindResource("oncrpc", subscribedResource).register();

            // 从独立消费者走真实网络，非默认 ONC 版本与业务字符串版本可以同时生效。
            Echo remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(subscribed);
            assertEquals("v2:resource-version", remote.echo("resource-version"));
            assertEquals("deployment-v1", published.getBindVersion());
            assertEquals("deployment-v1", subscribed.getBindVersion());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unannotatedServiceKeepsLocalCallsAvailable() throws Exception {
        // 未声明 ONC 程序的接口仍可注册本地服务。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<PlainEcho> service = server.publisher().rsfService(PlainEcho.class, value -> value).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            PlainEcho local = server.getRsfClient().getRemote(service);
            assertEquals("unannotated-local", local.echo("unannotated-local"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void customStructWithoutXdrCodecKeepsLocalCallsAvailable() throws Exception {
        // 自定义结构没有显式 XDR 编码器时，不生成 ONC 程序契约。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<OncRpcDemoService> service = server.publisher() //
                    .rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            OncRpcDemoService local = server.getRsfClient().getRemote(service);
            OncRpcSample value = new OncRpcSample();
            value.setText("local-without-xdr");
            assertSame(value, local.mirror(value));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void invalidProtocolResourceRejectsRegistration() throws Exception {
        // 当前协议显式绑定错误类型时，配置错误在发布阶段暴露。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher().rsfService(Echo.class, value -> value) //
                    .bindResource("oncrpc", "wrong-resource");

            // 无效绑定不能退回自动推导，也不能留下半次服务登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(Echo.class));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void foreignSerializerKeepsLocalServiceCallableAndRejectsNetworkCalls() throws Exception {
        // 提供者声明 JSON，消费者使用相同的 ONC 程序注解及默认 XDR 编码。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<Echo> published = server.publisher().rsfService(Echo.class, value -> value) //
                    .serialize("json").register();
            MetBindInfo<Echo> subscribed = client.publisher().rsfService(Echo.class).register();

            // 服务仍可通过公开入口本地调用，不因 ONC RPC 拒绝而撤销注册。
            assertNotNull(server.getServiceInfo(published.getBindID()));
            Echo local = server.getRsfClient().getRemote(published);
            assertEquals("local-json", local.echo("local-json"));

            // 网络端返回标准 PROG_UNAVAIL，不能把拒绝发布误当成调用超时。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                client.getRsfClient(server.getListenAddress("oncrpc")) //
                        .syncInvoke(subscribed, "echo", new Class<?>[] { String.class }, new Object[] { "remote" });
            });
            Throwable cause = failure.getCause();
            while (cause != null && !(cause instanceof OncRpcException)) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof OncRpcException);
            assertEquals(OncRpcMessage.PROG_UNAVAIL, ((OncRpcException) cause).getAcceptStatus());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void messageAnnotationKeepsLocalCallsAvailable() throws Exception {
        // ONC RPC 不支持消息注解声明的一次性通知。
        MetConfiguration configuration = this.incoming();
        AtomicReference<String> delivered = new AtomicReference<>();
        Message implementation = delivered::set;
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<Message> service = server.publisher().rsfService(Message.class, implementation).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            Message local = server.getRsfClient().getRemote(service);
            local.notify("local-message");
            assertEquals("local-message", delivered.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void messageBindingKeepsLocalCallsAvailable() throws Exception {
        // 服务级消息模式无法映射为 ONC 请求响应。
        MetConfiguration configuration = this.incoming();
        AtomicInteger calls = new AtomicInteger();
        Echo implementation = value -> {
            calls.incrementAndGet();
            return value;
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<Echo> service = server.publisher().rsfService(Echo.class, implementation) //
                    .asMessage().register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            Echo local = server.getRsfClient().getRemote(service);
            local.echo("local-message-binding");
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void flowReturnKeepsLocalCallsAvailable() throws Exception {
        // ONC RPC 的单次结果不支持 Flow 返回值。
        MetConfiguration configuration = this.incoming();
        Flow.Publisher<String> values = subscriber -> {
        };
        Streaming implementation = () -> values;
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<Streaming> service = server.publisher().rsfService(Streaming.class, implementation).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            Streaming local = server.getRsfClient().getRemote(service);
            assertSame(values, local.values());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void inputStreamReturnKeepsLocalCallsAvailable() throws Exception {
        // ONC RPC 不把 InputStream 当作普通 XDR 对象传输。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<BinaryStream> service = server.publisher() //
                    .rsfService(BinaryStream.class, () -> new ByteArrayInputStream(new byte[] { 42 })).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(server.getServiceInfo(service.getBindID()));
            BinaryStream local = server.getRsfClient().getRemote(service);
            try (InputStream bytes = local.bytes()) {
                assertEquals(42, bytes.read());
            }
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void scalarContractIsInferredFromPublicAnnotations() throws Exception {
        // 标量接口直接使用程序与过程注解，不要求用户创建绑定资源。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, value -> "inferred:" + value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            // 发起真实网络调用，证明发布与订阅都使用同一注解契约。
            Echo remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);
            assertEquals("inferred:hello", remote.echo("hello"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentProgramsShareOneProtocolEndpoint() throws Exception {
        // 同一 ONC RPC 协议挂载可以按程序编号区分不同 Java 服务。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, value -> "first:" + value).register();
            server.publisher().rsfService(OtherProgram.class, value -> "second:" + value).register();
            MetBindInfo<Echo> first = client.publisher().rsfService(Echo.class).register();
            MetBindInfo<OtherProgram> second = client.publisher().rsfService(OtherProgram.class).register();

            // 方法名相同也只能进入自己的程序，不能按 Java 名称错误择一。
            Echo firstRemote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(first);
            OtherProgram secondRemote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(second);
            assertEquals("first:one", firstRemote.echo("one"));
            assertEquals("second:two", secondRemote.echo("two"));
        }
    }

    @NxnRpc(name = "oncrpc", version = "2")
    @NxnRpc(name = "rsf", version = "1")
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void oncRpcAndRsfShareOneTcpListener() throws Exception {
        // 一个底层连接器同时挂载 RSF 与 ONC RPC，监听端口只创建一次。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("oncrpc");
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("oncrpc");
        listener.protocol("rsf");
        MetConfiguration oncConfiguration = this.outgoing();
        MetConfiguration rsfConfiguration = new MetConfiguration().setDefaultProtocol("rsf");
        rsfConfiguration.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext oncClient = oncConfiguration.buildContext(); //
             MetContext rsfClient = rsfConfiguration.buildContext()) {
            server.start();
            oncClient.start();
            rsfClient.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            server.publisher().rsfService(PlainEcho.class, value -> "rsf:" + value).protocol("rsf").register();
            MetBindInfo<OncRpcDemoService> onc = oncClient.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<PlainEcho> rsf = rsfClient.publisher().rsfService(PlainEcho.class) //
                    .protocol("rsf").register();

            // 两个独立客户端的首包探测必须选择各自协议，端口仍然相同。
            assertEquals(server.getListenAddress("oncrpc").getPort(), server.getListenAddress("rsf").getPort());
            OncRpcDemoService oncRemote = oncClient.getRsfClient(server.getListenAddress("oncrpc")).getRemote(onc);
            PlainEcho rsfRemote = rsfClient.getRsfClient(server.getListenAddress("rsf")).getRemote(rsf);
            assertEquals("onc", oncRemote.echo("onc"));
            assertEquals("rsf:other", rsfRemote.echo("other"));
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("oncrpc");
        return configuration;
    }

    private MetConfiguration outgoing() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        return configuration;
    }

    public interface PlainEcho {
        String echo(String value);
    }

    @MetService(id = 0x31234567L)
    public interface Echo {
        @MetMethod(id = 1)
        String echo(String value);
    }

    @MetService(id = 0x31234568L)
    public interface OtherProgram {
        @MetMethod(id = 1)
        String echo(String value);
    }

    @MetService(id = 0x31234567L)
    public interface Message {
        @MetMethod(id = 1)
        @MetMessage
        void notify(String value);
    }

    @MetService(id = 0x31234567L)
    public interface Streaming {
        @MetMethod(id = 1)
        Flow.Publisher<String> values();
    }

    @MetService(id = 0x31234567L)
    public interface BinaryStream {
        @MetMethod(id = 1)
        InputStream bytes();
    }
}
