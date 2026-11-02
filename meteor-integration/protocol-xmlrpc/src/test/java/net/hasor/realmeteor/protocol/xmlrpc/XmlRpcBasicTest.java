/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 单插件通过公开 bootstrap 发布、订阅和调用服务的基础验证。 */
public class XmlRpcBasicTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void javaProxyCallsOverHttp1() throws Exception {
        // 显式选择 HTTP/1，验证代理调用在实际网络上完成。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 服务代理只使用接口，不接触协议编解码实现。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("hello-xmlrpc", remote.echo("hello-xmlrpc"));
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }
}
