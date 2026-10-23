/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.net.URI;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.jsonrpc.JsonRpcJavaSdkServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 当前插件与独立 jsonrpc4j 实现的同 JVM 双向基础网络调用。 */
public class JsonRpcJavaSdkBasicTest {
    @Test(timeout = 30000)
    public void javaSdkCallsPublishedService() throws Throwable {
        // 当前 JUnit 进程通过正式入口启动 Meteor，并发布本插件的普通业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "demo").protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 直接使用 SDK 的公开 Java 客户端，仍通过随机监听端口完成真实 HTTP 往返。
            int port = server.getListenAddress("jsonrpc").getPort();
            String endpoint = "http://127.0.0.1:" + port + "/rpc";
            JsonRpcHttpClient sdk = new JsonRpcHttpClient(URI.create(endpoint).toURL());
            sdk.setConnectionTimeoutMillis(5000);
            sdk.setReadTimeoutMillis(10000);
            String result = sdk.invoke("demo.echo", new Object[] { "Java SDK 中文 <&>" }, String.class);
            assertEquals("Java SDK 中文 <&>", result);
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumerCallsJavaSdkServer() throws Exception {
        // SDK 服务与 Meteor 消费者均由测试持有，关闭顺序与创建顺序相反。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("jsonrpc").contextPath("/rpc");
        try (JsonRpcJavaSdkServer server = JsonRpcJavaSdkServer.start(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "demo").protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 前缀只能由 SDK 服务端添加，确保不是同 JVM 的本地调用捷径或普通回显。
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", server.getPort(), "default");
            JsonRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("sdk-server:Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }
}
