/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TApplicationException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 发布契约与多服务选择均通过正式发布 API 验证。 */
public class ThriftPublicationScenarioTest {
    @Test(timeout = 15000)
    public void missingIdlKeepsLocalServiceCallableAndRejectsNetworkCalls() throws Exception {
        // 提供者没有 IDL 资源，消费者具有相同 Java 接口及有效的生成契约。
        MetConfiguration incoming = this.incoming(false);
        MetConfiguration outgoing = this.outgoing(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<PlainFacade> published = server.publisher().rsfService(PlainFacade.class, value -> value) //
                    .protocol("thrift").register();
            MetBindInfo<PlainFacade> subscribed = client.publisher().rsfService(PlainFacade.class) //
                    .protocol("thrift").bindResource("thrift", resource).register();

            // 公开本地调用继续执行同一实现，不需要网络协议的 IDL 定义。
            assertNotNull(server.getServiceInfo(published.getBindID()));
            PlainFacade local = server.getRsfClient().getRemote(published);
            assertEquals("local-only", local.echo("local-only"));

            // 有效 Thrift 请求到达监听后收到 UNKNOWN_METHOD，不能进入被拒绝的服务。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift")));
            PlainFacade remote = caller.getRemote(subscribed);
            // 业务接口没有声明受检异常，JDK 代理会保留并包装 Thrift 的协议错误。
            UndeclaredThrowableException wrapped = assertThrows(UndeclaredThrowableException.class, //
                    () -> remote.echo("remote"));
            Throwable cause = wrapped.getUndeclaredThrowable();
            assertTrue(cause instanceof TApplicationException);
            TApplicationException failure = (TApplicationException) cause;
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
        }
    }

    @Test(timeout = 15000)
    public void invalidProtocolResourceRejectsRegistration() throws Exception {
        // 当前协议显式绑定错误类型时，配置错误在发布阶段暴露。
        MetConfiguration configuration = this.incoming(false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher().rsfService(PlainFacade.class, value -> value) //
                    .bindResource("thrift", "not-thrift-metadata");

            // 无效绑定不能退回自动推导，也不能留下半次服务登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(PlainFacade.class));
        }
    }

    @Test(timeout = 15000)
    public void overloadedBusinessMethodsRejectExplicitIdl() throws Exception {
        // IDL 不提供 Java 重载语义，同名方法不能互相覆盖。
        MetConfiguration configuration = this.incoming(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher() //
                    .rsfService(OverloadedFacade.class, new OverloadedFacadeImpl()) //
                    .bindResource("thrift", resource);

            // 显式契约冲突必须在发布时报错，并回收本次登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(OverloadedFacade.class));
        }
    }

    @Test(timeout = 15000)
    public void incompatibleReturnTypeRejectsExplicitIdl() throws Exception {
        // Java 返回类型必须与显式 IDL 契约一致。
        MetConfiguration configuration = this.incoming(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher() //
                    .rsfService(WrongReturnFacade.class, value -> 1) //
                    .bindResource("thrift", resource);

            // 显式契约冲突必须在发布时报错，并回收本次登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(WrongReturnFacade.class));
        }
    }

    @Test(timeout = 15000)
    public void streamingReturnTypeRejectsExplicitIdl() throws Exception {
        // Thrift 单次结果不能隐式承载 Flow 流。
        MetConfiguration configuration = this.incoming(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        Flow.Publisher<String> values = subscriber -> {
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher() //
                    .rsfService(StreamingFacade.class, value -> values) //
                    .bindResource("thrift", resource);

            // 显式契约冲突必须在发布时报错，并回收本次登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(StreamingFacade.class));
        }
    }

    @Test(timeout = 15000)
    public void unmarkedOnewayRejectsExplicitIdl() throws Exception {
        // IDL oneway 与 Java 消息声明必须一致。
        MetConfiguration configuration = this.incoming(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher() //
                    .rsfService(ExtendedInteropService.Iface.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource);

            // 显式契约冲突必须在发布时报错，并回收本次登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(ExtendedInteropService.Iface.class));
        }
    }

    @Test(timeout = 15000)
    public void incompatibleMessageDeclarationRejectsExplicitIdl() throws Exception {
        // 普通 IDL void 方法不能绑定为 Meteor 消息调用。
        MetConfiguration configuration = this.incoming(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            var publication = server.publisher() //
                    .rsfService(WrongMessageFacade.class, calls::incrementAndGet) //
                    .bindResource("thrift", resource);

            // 显式契约冲突必须在发布时报错，并回收本次登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(server.getServiceInfo(WrongMessageFacade.class));
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void nonMultiplexedEndpointRejectsAmbiguousServices() throws Exception {
        // 普通 Thrift 消息只有方法名，同一端点的两个提供者不能被静默择一。
        MetConfiguration incoming = this.incoming(false);
        MetConfiguration outgoing = this.outgoing(false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .name("first").bindResource("thrift", resource).register();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .name("second").bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .name("first").bindResource("thrift", resource).register();
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift")));
            ThriftDemoService remote = caller.getRemote(service);

            // 无服务前缀的消息必须返回标准 UNKNOWN_METHOD 错误。
            TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.echo("ambiguous"));
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
        }
    }

    @Test(timeout = 15000)
    public void multiplexedPrefixSelectsThePublishedService() throws Exception {
        // 两个提供者共用一个监听端口，服务别名成为 service:method 前缀。
        MetConfiguration incoming = this.incoming(true);
        MetConfiguration outgoing = this.outgoing(true);
        ThriftDemoServiceImpl first = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                return "first:" + value;
            }
        };
        ThriftDemoServiceImpl second = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                return "second:" + value;
            }
        };
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, first) //
                    .name("first").aliasName("thrift", "First").bindResource("thrift", resource).register();
            server.publisher().rsfService(ThriftDemoService.class, second) //
                    .name("second").aliasName("thrift", "Second").bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> firstService = client.publisher().rsfService(ThriftDemoService.class) //
                    .name("first").aliasName("thrift", "First").bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> secondService = client.publisher().rsfService(ThriftDemoService.class) //
                    .name("second").aliasName("thrift", "Second").bindResource("thrift", resource).register();

            // 相同方法名按前缀路由到不同业务对象，不能交叉返回结果。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift")));
            assertEquals("first:one", caller.getRemote(firstService).echo("one"));
            assertEquals("second:two", caller.getRemote(secondService).echo("two"));
        }
    }

    @Test(timeout = 15000)
    public void framedThriftAndRsfShareOneTcpPort() throws Exception {
        // 一个 TCP 连接器挂载两个协议；探测必须保留 RSF magic 与 Thrift 长度头的区别。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("thrift");
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("thrift");
        listener.protocol("rsf");
        MetConfiguration thriftConfiguration = this.outgoing(false);
        MetConfiguration rsfConfiguration = new MetConfiguration().setDefaultProtocol("rsf");
        rsfConfiguration.connector("tcp").protocol("rsf");
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext thriftClient = thriftConfiguration.buildContext(); //
             MetContext rsfClient = rsfConfiguration.buildContext()) {
            server.start();
            thriftClient.start();
            rsfClient.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift").bindResource("thrift", resource).register();
            server.publisher().rsfService(PlainFacade.class, value -> "rsf:" + value) //
                    .protocol("rsf").register();
            MetBindInfo<ThriftDemoService> thriftService = thriftClient.publisher() //
                    .rsfService(ThriftDemoService.class).protocol("thrift").bindResource("thrift", resource).register();
            MetBindInfo<PlainFacade> rsfService = rsfClient.publisher().rsfService(PlainFacade.class) //
                    .protocol("rsf").register();

            // 两个实际客户端使用同一监听端口，分别完成自己的业务调用。
            int thriftPort = server.getListenAddress("thrift").getPort();
            assertEquals(thriftPort, server.getListenAddress("rsf").getPort());
            ThriftClient caller = new ThriftClient(thriftClient.getRsfClient(server.getListenAddress("thrift")));
            assertEquals("thrift", caller.getRemote(thriftService).echo("thrift"));
            PlainFacade rsf = rsfClient.getRsfClient(server.getListenAddress("rsf")).getRemote(rsfService);
            assertEquals("rsf:other", rsf.echo("other"));
        }
    }

    private MetConfiguration incoming(boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("thrift").option("multiplexed", multiplexed);
        return configuration;
    }

    private MetConfiguration outgoing(boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").protocol("thrift").option("multiplexed", multiplexed);
        return configuration;
    }

    public interface PlainFacade {
        String echo(String value);
    }

    public interface OverloadedFacade {
        String echo(String value);

        String echo(int value);
    }

    public static class OverloadedFacadeImpl implements OverloadedFacade {
        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String echo(int value) {
            return Integer.toString(value);
        }
    }

    public interface WrongReturnFacade {
        int echo(String value);
    }

    public interface StreamingFacade {
        Flow.Publisher<String> echo(String value);
    }

    public interface WrongMessageFacade {
        @MetMessage
        void ping();
    }
}
