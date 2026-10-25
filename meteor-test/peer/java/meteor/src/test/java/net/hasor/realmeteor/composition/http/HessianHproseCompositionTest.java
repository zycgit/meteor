/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import net.hasor.example.composition.CompositionEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HessianHproseCompositionTest {
    @Test(timeout = 20000)
    public void hessianAndHproseUseSeparateContextsOnOneHttpPort() throws Exception {
        // 两个真实协议插件挂载到同一个 HTTP 端点，复用两者都支持的普通回显接口。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("hessian");
        ConnectorSettings endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("hessian").contextPath("/hessian");
        endpoint.protocol("hprose").contextPath("/hprose");
        MetConfiguration hessian = new MetConfiguration().setDefaultProtocol("hessian");
        hessian.connector("http").protocol("hessian").contextPath("/hessian");
        MetConfiguration hprose = new MetConfiguration().setDefaultProtocol("hprose");
        hprose.connector("http").protocol("hprose").contextPath("/hprose");
        try (MetContext server = incoming.buildContext(); //
             MetContext hessianClient = hessian.buildContext(); //
             MetContext hproseClient = hprose.buildContext()) {
            server.start();
            hessianClient.start();
            hproseClient.start();
            server.publisher().rsfService(CompositionEchoService.class, value -> value) //
                    .aliasName("hessian", "demo").aliasName("hprose", "demo") //
                    .protocol("hessian", "hprose").serialize("Hessian").register();
            MetBindInfo<CompositionEchoService> hessianService = hessianClient.publisher() //
                    .rsfService(CompositionEchoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<CompositionEchoService> hproseService = hproseClient.publisher() //
                    .rsfService(CompositionEchoService.class) //
                    .aliasName("hprose", "demo").serialize("Hprose").register();

            // 各协议自己的编码与服务定位保持隔离，同时确实复用了监听端口。
            int port = server.getListenAddress("hessian").getPort();
            assertEquals(port, server.getListenAddress("hprose").getPort());
            CompositionEchoService first = hessianClient.getRsfClient(server.getListenAddress("hessian")) //
                    .getRemote(hessianService);
            CompositionEchoService second = hproseClient.getRsfClient(server.getListenAddress("hprose")) //
                    .getRemote(hproseService);
            assertEquals("hessian", first.echo("hessian"));
            assertEquals("hprose", second.echo("hprose"));
        }
    }
}
