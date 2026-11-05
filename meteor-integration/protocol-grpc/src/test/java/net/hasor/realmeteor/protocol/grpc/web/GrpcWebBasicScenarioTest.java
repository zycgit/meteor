/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 插件自身的 Java 提供者和消费者，通过公开入口完成真实 gRPC-Web 调用。 */
public class GrpcWebBasicScenarioTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void bootstrapPublishesAndConsumesJsonServiceWithoutAnAlias() throws Exception {
        // HTTP/1 上仅挂载 grpc-web，客户端不开放监听端口。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("grpc-web").contextPath("/web").option("streamBody", true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc-web");
        outgoing.connector("http").option("httpVersion", 1) //
                .protocol("grpc-web").contextPath("/web").option("streamBody", true);
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 提供者发布实现，消费者只订阅接口，必须通过远程端点调用。
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .serialize("Json").register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class) //
                    .serialize("Json").register();

            // 业务值和服务端调用次数一起证明实际调用了已发布的实现。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("你好 gRPC-Web 🌏", 6));
            assertEquals("你好 gRPC-Web 🌏", response.getValue());
            assertEquals(7, response.getCount());
            assertEquals(1, implementation.getCalls());
        }
    }
}
