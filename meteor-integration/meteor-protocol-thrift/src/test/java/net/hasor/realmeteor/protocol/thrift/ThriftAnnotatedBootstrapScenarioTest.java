/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.mapping.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 普通接口的发布、引用和 DTO 往返全部经过 bootstrap。 */
public class ThriftAnnotatedBootstrapScenarioTest {
    @Test(timeout = 15000)
    public void ordinaryDtoRoundTripsWithNestedContainers() throws Exception {
        // 两个独立容器使用普通 Java 接口，异常字段通过 IDL 声明。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NoteService.class, new NoteServiceImpl()) //
                    .bindResource("thrift", this.resource()).register();
            MetBindInfo<NoteService> service = client.publisher().rsfService(NoteService.class) //
                    .bindResource("thrift", this.resource()).register();
            NoteService remote = client.getRsfClient(server.getListenAddress("thrift")).getRemote(service);

            // 递归 DTO 在容器内使用声明泛型，未设置的可选数值保持 null。
            Note child = new Note();
            child.setId(9);
            child.setText("child");
            Note input = new Note();
            input.setId(7);
            input.setText("中文");
            input.setChildren(Map.of("notes", List.of(child)));
            Note result = remote.echo(input);
            assertNotSame(input, result);
            assertEquals("中文", result.getText());
            assertEquals(7, result.getId());
            assertNull(result.getPriority());
            assertEquals(9, result.getChildren().get("notes").get(0).getId());
        }
    }

    @Test(timeout = 15000)
    public void methodAliasKeepsJavaArgumentOrder() throws Exception {
        // 差值调用使用不同的 Java 和线协议方法名，编号也不按位置递增。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NoteService.class, new NoteServiceImpl()) //
                    .bindResource("thrift", this.resource()).register();
            MetBindInfo<NoteService> service = client.publisher().rsfService(NoteService.class) //
                    .bindResource("thrift", this.resource()).register();

            // 真实网络调用还原 Java subtract，不能交换两个同类型参数。
            NoteService remote = client.getRsfClient(server.getListenAddress("thrift")).getRemote(service);
            assertEquals(15, remote.subtract(19, 4));
        }
    }

    @Test(timeout = 15000)
    public void ordinaryDeclaredExceptionReachesTheTypedClient() throws Exception {
        // Java 异常只继承 Exception，异常字段编号来自绑定的 IDL。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NoteService.class, new NoteServiceImpl()) //
                    .bindResource("thrift", this.resource()).register();
            MetBindInfo<NoteService> service = client.publisher().rsfService(NoteService.class) //
                    .bindResource("thrift", this.resource()).register();
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift")));
            NoteService remote = caller.getRemote(service);

            // 适配器保留接口声明的普通异常，不要求用户继承 TException。
            NoteFailure failure = assertThrows(NoteFailure.class, () -> remote.fail(409));
            assertEquals(409, failure.getCode());
        }
    }

    @Test(timeout = 15000)
    public void messageAnnotationUsesTheExistingOnewayLifecycle() throws Exception {
        // 消息方法只复用 MetMessage，业务接口不引入第二套单向调用注解。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            NoteServiceImpl implementation = new NoteServiceImpl();
            server.publisher().rsfService(NoteService.class, implementation) //
                    .bindResource("thrift", this.resource()).register();
            MetBindInfo<NoteService> service = client.publisher().rsfService(NoteService.class) //
                    .bindResource("thrift", this.resource()).register();
            NoteService remote = client.getRsfClient(server.getListenAddress("thrift")).getRemote(service);
            remote.notify("event");

            // 有界等待业务消费结果，而不是把发送完成当成服务已执行。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (implementation.notificationCount() == 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(1, implementation.notificationCount());
        }
    }

    private ThriftIdlResource resource() throws Exception {
        return new ThriftIdlResource(this.getClass().getResource("/thrift/mapping.thrift")) //
                .selectService("NoteService");
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        MetConfiguration.ConnectorSettings connector = configuration.connector("tcp");
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("thrift").option("encoding", "compact");
        return configuration;
    }
}
