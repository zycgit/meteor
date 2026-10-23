/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.net.URI;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.xmlrpc.XmlRpcJavaSdkServer;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 当前插件与原生 Java SDK 的同 JVM 双向基础网络调用。 */
public class XmlRpcJavaSdkBasicTest {
    @Test(timeout = 30000)
    public void officialJavaSdkCallsPublishedService() throws Exception {
        // 当前 JUnit 进程通过正式入口启动 Meteor，并发布本插件的普通业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc") //
                    .register();

            // 直接使用 SDK 的公开 Java 客户端，仍通过随机监听端口完成真实 HTTP 往返。
            int port = server.getListenAddress("xmlrpc").getPort();
            String endpoint = "http://127.0.0.1:" + port + "/rpc";
            XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
            config.setServerURL(URI.create(endpoint).toURL());
            config.setConnectionTimeout(5000);
            config.setReplyTimeout(10000);
            config.setEnabledForExtensions(false);
            XmlRpcClient sdk = new XmlRpcClient();
            sdk.setConfig(config);
            Object result = sdk.execute("demo.echo", new Object[] { "Java SDK 中文 <&>" });
            assertEquals("Java SDK 中文 <&>", result);
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumerCallsOfficialJavaSdkServer() throws Exception {
        // SDK 服务与 Meteor 消费者均由测试持有，关闭顺序与创建顺序相反。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc").contextPath("/rpc");
        try (XmlRpcJavaSdkServer server = XmlRpcJavaSdkServer.start(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc") //
                    .register();

            // 前缀只能由 SDK 服务端添加，确保不是同 JVM 的本地调用捷径或普通回显。
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", server.getPort(), "default");
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("sdk-server:Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }
}
