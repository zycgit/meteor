/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.util.concurrent.Flow;
import java.util.function.IntBinaryOperator;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** 从公开启动与发布入口验证协议拒绝不会撤销其他发布或本地实现。 */
public class GrpcPublicationScenarioTest {
    @Test
    public void providerWithoutProtoRemainsLocallyCallable() throws Exception {
        // 两个传输端点接收同一个提供者，Protobuf 服务没有绑定 proto。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.connector("http").protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").serialize("Protobuf").register();

            // 协议不能承载当前声明时，公开服务查询仍能看到本地提供者。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());

            // 本地优先调用直接使用实现，不依赖被拒绝的网络协议。
            Echo local = context.getRsfClient().getRemote(service);
            Message result = local.echo(Message.of("local", 5));
            assertEquals("local", result.getValue());
            assertEquals(6, result.getCount());
        }
    }

    @Test
    public void unsupportedGrpcMethodRemainsLocallyCallable() throws Exception {
        // 双参数 Java 方法无法对应一条 gRPC 请求，仍可由 RSF 或本地服务承载。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.connector("http").protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<IntBinaryOperator> service = context.publisher() //
                    .rsfService(IntBinaryOperator.class, (left, right) -> left + right) //
                    .aliasName("grpc", "example.Sum").serialize("Json").register();

            // 网络协议不支持的签名仍可作为本地服务登记和执行。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            IntBinaryOperator local = context.getRsfClient().getRemote(service);
            assertEquals(7, local.applyAsInt(3, 4));
        }
    }

    @Test
    public void browserClientStreamRejectionKeepsLocalService() throws Exception {
        // 标准 gRPC 支持客户端流，浏览器适配只拒绝自身无法表达的服务。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("grpc").contextPath("/grpc");
        http.protocol("grpc-web").contextPath("/web");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            BrowserUpload implementation = new BrowserUpload() {
                @Override
                public String echo(String value) {
                    return "local:" + value;
                }

                @Override
                public String upload(Flow.Publisher<String> values) {
                    return "accepted";
                }
            };
            MetBindInfo<BrowserUpload> service = context.publisher() //
                    .rsfService(BrowserUpload.class, implementation) //
                    .aliasName("grpc", "example.Upload") //
                    .aliasName("grpc-web", "example.Upload").serialize("Json").register();

            // 浏览器适配不支持该接口时，本地服务仍通过公开入口可见且可调用。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            BrowserUpload local = context.getRsfClient().getRemote(service);
            assertEquals("local:value", local.echo("value"));
        }
    }

    @Test(timeout = 30000)
    public void sameProtocolCallsEachEndpointWithItsOwnContext() throws Exception {
        // 提供者在两个端口挂载同名协议，各自选择不同地址前缀和默认 context。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .addressScheme("grpc-primary").contextPath("/primary");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .addressScheme("grpc-secondary").contextPath("/secondary");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc") //
                .addressScheme("grpc-primary").contextPath("/primary");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc") //
                .addressScheme("grpc-secondary").contextPath("/secondary");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();

            // 消费者根据目标地址选择第一端点，使用该端点的默认 context 完成真实 RPC。
            InterAddress primaryAddress = server.getListenAddress("grpc", "grpc-primary");
            Echo primary = client.getRsfClient(primaryAddress).getRemote(service);
            Message first = primary.echo(Message.of("primary", 1));
            assertEquals("primary", first.getValue());
            assertEquals(2, first.getCount());

            // 相同接口再次经过第二端点，不能复用第一端点的路径或地址前缀。
            InterAddress secondaryAddress = server.getListenAddress("grpc", "grpc-secondary");
            Echo secondary = client.getRsfClient(secondaryAddress).getRemote(service);
            Message second = secondary.echo(Message.of("secondary", 3));
            assertEquals("secondary", second.getValue());
            assertEquals(4, second.getCount());
        }
    }

    public interface BrowserUpload {
        String echo(String value);

        String upload(Flow.Publisher<String> values);
    }
}
