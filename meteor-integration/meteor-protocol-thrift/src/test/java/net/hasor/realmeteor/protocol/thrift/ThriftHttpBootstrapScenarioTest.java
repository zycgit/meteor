/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** 插件公开启动入口的基础发布与调用验证。 */
public class ThriftHttpBootstrapScenarioTest {

    @Test(timeout = 15000)
    public void binaryProxyCallsOverHttp1() throws Exception {
        // 不设置 encoding，验证默认 binary 编码，客户端选择 HTTP/1。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1, "binary");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource(new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource(new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/1 binary 中文", remote.echo("HTTP/1 binary 中文"));
        }
    }

    @Test(timeout = 15000)
    public void rejectedThriftPublicationStillAllowsLocalInvocation() throws Exception {
        // 同一个 Java 实现选择 JSON 后不支持原生 Thrift，但本地调用不需要其线契约。
        MetConfiguration configuration = this.serverConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<ThriftDemoService> service = context.publisher() //
                    .rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .serialize("json").register();

            // 提供者仍登记在容器中，协议拒绝仅移除当前协议的可达端点。
            ThriftDemoService local = context.getRsfClient().getRemote(service);
            assertEquals("local value", local.echo("local value"));
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
        }
    }

    @Test(timeout = 15000)
    public void rejectedThriftPublicationStillAllowsRsfNetworkInvocation() throws Exception {
        // RSF 与 Thrift HTTP 共用业务容器，当前接口选用的 JSON 仅适用于 RSF。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("thrift-http").contextPath("/thrift");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<ThriftDemoService> published = server.publisher() //
                    .rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .serialize("json").register();
            MetBindInfo<ThriftDemoService> subscribed = client.publisher() //
                    .rsfService(ThriftDemoService.class).serialize("json").register();

            // 独立消费者通过真实 RSF 监听地址调用，拒绝结果不会污染已接受协议。
            ThriftDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(subscribed);
            assertEquals("rsf value", remote.echo("rsf value"));
            MetBindInfo<?> registered = server.getServiceInfo(published.getBindID());
            assertNotNull(registered);
            assertEquals(published.getBindID(), registered.getBindID());
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("thrift-http").contextPath("/thrift");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int version, String encoding) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        MetConfiguration.ProtocolSettings protocol = configuration.connector("http").option("httpVersion", version) //
                .protocol("thrift-http").contextPath("/thrift");
        if (!StringUtils.equals("binary", encoding)) {
            protocol.option("encoding", encoding);
        }
        return configuration;
    }
}
