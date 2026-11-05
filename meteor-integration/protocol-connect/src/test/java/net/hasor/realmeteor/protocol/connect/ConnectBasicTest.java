/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 单插件通过公开 bootstrap 发布、订阅和调用服务的基础验证。 */
public class ConnectBasicTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonUnaryUsesTheInterfaceNameWithoutAnAlias() throws Exception {
        // 从公开配置入口挂载 Connect，客户端不开放监听端口。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动完成后再发布与订阅业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 普通 Java 代理发送一次 RPC，编码不影响最终业务类型。
            ConnectMessage result = remote.echo(ConnectMessage.of("你好 Connect 🌏", 6));
            assertEquals("你好 Connect 🌏", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void protobufUnaryUsesTheDeclaredProtoServiceNameWithoutAnAlias() throws Exception {
        // 登记名称与 proto 完整服务名对齐，无需额外配置协议别名。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .name("meteor.connect.demo.ConnectDemoService").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .name("meteor.connect.demo.ConnectDemoService").serialize("Protobuf") //
                    .bindResource("connect", resource).register();

            // 请求路径、服务匹配和消息契约使用同一个有效服务名。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage result = remote.echo(ConnectMessage.of("proto default", 4));
            assertEquals("proto default", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test
    public void aDefaultNameAndAnExplicitAliasCannotClaimTheSameRoute() throws Exception {
        // 同一挂载点的两个登记身份不能共享一个 Connect 线上名称。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .name("orders.OrderService").serialize("Json").register();

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                        .name("internal.OrderService").aliasName("connect", "orders.OrderService") //
                        .serialize("Json").register();
            });
            assertTrue(failure.getMessage().contains("orders.OrderService"));
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("connect").contextPath("/rpc").option("streamBody", true);
        return configuration;
    }
}
