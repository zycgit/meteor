/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import hprose.client.HproseHttpClient;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.hprose.HproseJavaSdkServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 当前插件与原生 Java SDK 的同 JVM 双向基础网络调用。 */
public class HproseJavaSdkBasicTest {
    @Test(timeout = 30000)
    public void officialJavaSdkCallsPublishedService() throws Exception {
        // 当前 JUnit 进程通过正式入口启动 Meteor，并发布本插件的普通业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(Echo.class, new EchoService()) //
                    .aliasName("Hprose", "demo").protocol("hprose") //
                    .serialize("Hprose") //
                    .register();

            // 直接使用 SDK 的公开 Java 客户端，仍通过随机监听端口完成真实 HTTP 往返。
            int port = server.getListenAddress("hprose").getPort();
            String endpoint = "http://127.0.0.1:" + port + "/rpc";
            HproseHttpClient sdk = new HproseHttpClient(endpoint);
            try {
                sdk.setTimeout(10000);
                sdk.setRetry(0);
                Echo remote = sdk.useService(Echo.class, "demo");
                assertEquals("Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
            } finally {
                sdk.close();
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumerCallsOfficialJavaSdkServer() throws Exception {
        // SDK 服务与 Meteor 消费者均由测试持有，关闭顺序与创建顺序相反。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hprose").contextPath("/rpc");
        try (HproseJavaSdkServer server = HproseJavaSdkServer.start(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<Echo> service = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("Hprose", "demo").protocol("hprose") //
                    .serialize("Hprose") //
                    .register();

            // 前缀只能由 SDK 服务端添加，确保不是同 JVM 的本地调用捷径或普通回显。
            InterAddress address = new InterAddress("hprose", "127.0.0.1", server.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(service);
            assertEquals("sdk-server:Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }
}
