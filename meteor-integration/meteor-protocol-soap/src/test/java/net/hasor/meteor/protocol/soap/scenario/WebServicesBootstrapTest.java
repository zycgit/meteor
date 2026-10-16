/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.scenario.example.EchoServiceImpl;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebServicesBootstrapTest {
    @Test
    public void meteorClientCallsPublishedSoapService() throws Exception {
        // 服务端监听 SOAP，客户端只准备出站 SOAP 通道。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端只发布和订阅 Java 接口，由 SOAP 扩展自动生成相同契约。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 通过公开 MetClient 代理走真实 HTTP，验证 SOAP request-response 往返。
            MetClient caller = client.getRsfClient(server.bindAddress("soap"));
            EchoService remote = caller.getRemote(subscribed);
            assertEquals("hello SOAP", remote.echo("hello SOAP"));
        }
    }
}
