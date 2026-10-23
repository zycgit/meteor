/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.xmlrpc;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 使用插件 SPI 配置并发布一个 XML-RPC 服务。 */
public class XmlRpcExample {
    public static void main(String[] arguments) throws Exception {
        // 端点选择 HTTP 传输和 XML-RPC 协议，业务代码无需感知具体实现。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("xmlrpc");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("xmlrpc");
        outgoing.connector("http").protocol("xmlrpc").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动容器，再发布与订阅同一个普通 Java 接口。
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

            // 客户端通过正式接口代理发起网络 RPC。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            System.out.println("echo=" + remote.echo("hello-xmlrpc"));
            System.out.println("difference=" + remote.subtract(7, 5));
        }
    }
}
