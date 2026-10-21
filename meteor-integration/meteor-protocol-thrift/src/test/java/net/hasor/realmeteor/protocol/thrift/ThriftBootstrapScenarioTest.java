/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 插件公开启动入口的基础发布与调用验证。 */
public class ThriftBootstrapScenarioTest {
    @Test(timeout = 15000)
    public void binaryFramedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource(resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource(resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // binary 编码与 Framed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    private MetConfiguration incoming(String encoding, boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("thrift").option("encoding", encoding).option("framed", framed);
        return configuration;
    }

    private MetConfiguration outgoing(String encoding, boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").protocol("thrift") //
                .option("encoding", encoding).option("framed", framed);
        return configuration;
    }
}
