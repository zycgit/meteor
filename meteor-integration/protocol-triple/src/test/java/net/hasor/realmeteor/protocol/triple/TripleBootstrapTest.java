/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
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

/** 插件从公开 bootstrap 完成一次基本网络调用。 */
public class TripleBootstrapTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void wrapperUnaryUsesTheInterfaceNameWithoutAnAlias() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // Hessian2 wrapper 恢复原始业务 DTO，而不是暴露协议包装对象。
            TripleMessage result = remote.echo(TripleMessage.of("你好 Triple 🌏", 6));
            assertEquals("你好 Triple 🌏", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void protobufUnaryUsesTheDeclaredProtoServiceNameWithoutAnAlias() throws Exception {
        // 原生 Protobuf 的默认登记名必须与绑定的 proto 服务名一致。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .name("meteor.triple.demo.TripleFlowService").serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .name("meteor.triple.demo.TripleFlowService").serialize("Protobuf") //
                    .bindResource("triple", resource).register();

            // 同一个名称贯穿出站路径、入站路由及原生消息映射。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage result = remote.echo(TripleMessage.of("proto default", 4));
            assertEquals("proto default", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test
    public void aDefaultNameAndAnExplicitAliasCannotClaimTheSameRoute() throws Exception {
        // 分组和版本也相同时，默认名与别名不能指向两个不同提供者。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .name("orders.OrderService").serialize("Hessian2").register();

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                        .name("internal.OrderService").aliasName("triple", "orders.OrderService") //
                        .serialize("Hessian2").register();
            });
            assertTrue(failure.getMessage().contains("orders.OrderService"));
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
