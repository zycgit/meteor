/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 无 proto 的 Java 接口通过 Triple wrapper 直接调用。 */
public class TriplePojoScenarioTest {
    @Test(timeout = 15000)
    public void multipleArgumentsKeepTheirDeclaredPositions() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // Java 参数顺序与 wrapper 的参数类型列表一致。
            assertEquals("你好你好你好", remote.greet("你好", 3));
        }
    }

    @Test(timeout = 15000)
    public void stringOverloadUsesTheStringSignature() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 同名方法通过完整参数类型选择，不以 JSON 值猜测业务重载。
            assertEquals("text:42", remote.overloaded("42"));
        }
    }

    @Test(timeout = 15000)
    public void integerOverloadUsesThePrimitiveSignature() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // primitive 参数签名不能错误匹配字符串重载。
            assertEquals("number:42", remote.overloaded(42));
        }
    }

    @Test(timeout = 15000)
    public void collectionValuesRetainTheirBusinessElementTypes() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 集合是普通业务数据，元素仍应恢复为声明的 DTO 类型。
            List<TripleMessage> input = List.of(TripleMessage.of("first", 2), TripleMessage.of("second", 3));
            List<TripleMessage> result = remote.list(input);
            assertEquals(2, result.size());
            assertEquals("second", result.get(1).getText());
            assertEquals(3, result.get(1).getNumber());
        }
    }

    @Test(timeout = 15000)
    public void voidMethodStillCompletesAnOrdinaryRpc() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // void 是普通 RPC 的空返回值，不等同于无响应通知。
            assertNull(client.getRsfClient(server.getListenAddress("triple")) //
                    .asyncInvoke(service, "accept", new Class<?>[] { TripleMessage.class }, //
                            new Object[] { TripleMessage.of("accepted", 5) }).getData(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void nullBusinessResultIsPreserved() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // wrapper 明确表达 null，不制造空字符串或空 DTO。
            assertNull(remote.list(null));
        }
    }

    @Test(timeout = 15000)
    public void businessFailureReachesTheCallingFuture() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 普通 POJO 调用通过 wrapper 传回业务 Throwable，公开 Future 仍以失败结束。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] values = { TripleMessage.of("failure", 1) };
            MetFuture pending = caller.asyncInvoke(service, "fail", types, values);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            Throwable businessFailure = failure.getCause();
            while (businessFailure != null && !(businessFailure instanceof IllegalArgumentException)) {
                businessFailure = businessFailure.getCause();
            }
            assertNotNull("The original business exception must remain observable", businessFailure);
            assertEquals(IllegalArgumentException.class, businessFailure.getClass());
            assertEquals("triple failure 中文", businessFailure.getMessage());
            assertNull(GrpcStatusException.find(failure));

            // 业务失败只终止当前 RPC，同一客户端的后续调用仍使用有效连接。
            TripleMessage following = remote.echo(TripleMessage.of("following", 7));
            assertEquals("following", following.getText());
            assertEquals(8, following.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void concurrentHttp2CallsRemainIsolated() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 一次提交并发请求，让 HTTP/2 的每个 stream 保有自己的调用结果。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            List<MetFuture> pending = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                Object[] values = { TripleMessage.of("call-" + index, index) };
                pending.add(caller.asyncInvoke(service, "echo", types, values));
            }

            // 完成顺序可以变化，每个 Future 仍只交付自己的业务值。
            for (int index = 0; index < pending.size(); index++) {
                TripleMessage result = (TripleMessage) pending.get(index).getData(5, TimeUnit.SECONDS);
                assertEquals("call-" + index, result.getText());
                assertEquals(index + 1, result.getNumber());
            }
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
