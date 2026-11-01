/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import com.caucho.hessian.client.HessianProxyFactory;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.hessian.HessianJavaSdkServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 当前插件与原生 Java SDK 的同 JVM 双向基础网络调用。 */
public class HessianJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaSdkCallsPublishedService() throws Exception {
        // 当前 JUnit 进程通过正式入口启动 Meteor，并发布本插件的普通业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc") //
                .option("version", 2);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian") //
                    .serialize("Hessian") //
                    .register();

            // 直接使用 SDK 的公开 Java 客户端，仍通过随机监听端口完成真实 HTTP 往返。
            int port = server.getListenAddress("hessian").getPort();
            String endpoint = "http://127.0.0.1:" + port + "/rpc/demo";
            HessianProxyFactory factory = new HessianProxyFactory();
            factory.setHessian2Request(true);
            factory.setHessian2Reply(true);
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(10000);
            HessianDemoService remote = (HessianDemoService) factory.create(HessianDemoService.class, endpoint);
            assertEquals("Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsOfficialJavaSdkServer() throws Exception {
        // SDK 服务与 Meteor 消费者均由测试持有，关闭顺序与创建顺序相反。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/rpc") //
                .option("version", 2);
        try (HessianJavaSdkServer server = HessianJavaSdkServer.start(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<HessianDemoService> service = client.publisher() //
                    .rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").protocol("hessian") //
                    .serialize("Hessian") //
                    .register();

            // 前缀只能由 SDK 服务端添加，确保不是同 JVM 的本地调用捷径或普通回显。
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("sdk-server:Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }
}
