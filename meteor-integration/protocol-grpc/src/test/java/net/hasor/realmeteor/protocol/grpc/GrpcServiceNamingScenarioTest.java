/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** 服务的有效名称同时用于发布冲突校验和真实 RPC 路由。 */
@Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class GrpcServiceNamingScenarioTest {
    @Test
    public void serviceAnnotationSuppliesTheDefaultName() throws Exception {
        // 双方通过公共服务注解命名，均不配置 gRPC 别名。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NamedEcho.class, request -> request) //
                    .serialize("Json").register();
            MetBindInfo<NamedEcho> service = client.publisher().rsfService(NamedEcho.class) //
                    .serialize("Json").register();

            // 注解名参与实际请求路径，仍然保留原本的服务身份。
            assertEquals("catalog.NamedEcho", service.getBindName());
            NamedEcho remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            Message response = remote.echo(Message.of("named", 3));
            assertEquals("named", response.getValue());
            assertEquals(3, response.getCount());
        }
    }

    @Test
    public void aliasCollisionWithADefaultNamePreservesTheExistingProvider() throws Exception {
        // 首个服务使用推导名称，另一个不同服务身份的别名将与它冲突。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, implementation).serialize("Json").register();

            // 同一挂载上的有效名称必须唯一，失败不能撤销之前的发布。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(Echo.class, new EchoImpl()) //
                        .name("catalog.OtherEcho").group("collision").version("1") //
                        .aliasName("grpc", Echo.class.getName()) //
                        .serialize("Json").register();
            });
            assertTrue(failure.getMessage().contains(Echo.class.getName()));
            assertNull(server.getServiceInfo("collision", "catalog.OtherEcho", "1"));

            // 原服务经过真实网络仍能正确处理调用。
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).serialize("Json").register();
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            Message response = remote.echo(Message.of("existing", 4));
            assertEquals("existing", response.getValue());
            assertEquals(5, response.getCount());
            assertEquals(1, implementation.getCalls());
        }
    }

    @Test
    public void differentMountsCanExposeTheSameEffectiveName() throws Exception {
        // 一个端点声明两个目录，两个提供者分别选择自己的挂载。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/first").addContextPath("/second");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc") //
                .contextPath("/first").addContextPath("/second");
        EchoImpl first = new EchoImpl();
        EchoImpl second = new EchoImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, first).name("catalog.Echo") //
                    .serialize("Json").contextPathByPrefix("grpc", "/first").register();
            server.publisher().rsfService(Echo.class, second).name("catalog.OtherEcho") //
                    .aliasName("grpc", "catalog.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/second").register();

            // 消费者携带对应目录，同名服务不能越过自己的挂载调用另一个提供者。
            MetBindInfo<Echo> firstService = client.publisher().rsfService(Echo.class).name("catalog.Echo") //
                    .serialize("Json").contextPathByPrefix("grpc", "/first").register();
            MetBindInfo<Echo> secondService = client.publisher().rsfService(Echo.class).name("catalog.OtherEcho") //
                    .aliasName("grpc", "catalog.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/second").register();
            Echo firstRemote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(firstService);
            Echo secondRemote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(secondService);
            assertEquals("first", firstRemote.echo(Message.of("first", 1)).getValue());
            assertEquals("second", secondRemote.echo(Message.of("second", 2)).getValue());
            assertEquals(1, first.getCalls());
            assertEquals(1, second.getCalls());
        }
    }

    @MetService(name = "catalog.NamedEcho")
    public interface NamedEcho {
        Message echo(Message request);
    }
}
