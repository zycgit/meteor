/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 插件基础验证：仅用自身公开 API 完成一次 Protobuf 调用。 */
public class GrpcBasicScenarioTest {
    @Test(timeout = 20000)
    public void bootstrapPublishesAndConsumesProtobufService() throws Exception {
        // 声明服务端监听和客户端通道，两端使用同一份本地 proto 契约。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动上下文，再通过发布入口登记提供者和消费者。
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", resource).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", resource).register();

            // 通过公开客户端完成真实 HTTP/2 调用，核对业务结果。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            Message response = remote.echo(Message.of("basic grpc", 7));
            assertEquals("basic grpc", response.getValue());
            assertEquals(8, response.getCount());
        }
    }
}
