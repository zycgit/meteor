/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.triple.WireEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 同一 JUnit 进程内，官方 Triple 3.2.20 SDK 与 Meteor 通过真实网络完成双向基础调用。 */
public class TripleJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaSdkCallsPublishedHessian2Service() throws Exception {
        // SDK 会规范化普通 loopback 直连地址，因此 Meteor 监听本机接口的随机端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind("0.0.0.0", 0) //
                .protocol("triple").option("streamBody", true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            WireEcho implementation = value -> {
                calls.incrementAndGet();
                return value;
            };
            server.publisher().rsfService(WireEcho.class, implementation) //
                    .aliasName("triple", WireEcho.class.getName()).group("sdk-basic").version("1.0.0") //
                    .serialize("Hessian2").register();

            // 标准 ReferenceConfig 禁用本地短路，完整线上调用由官方 SDK 完成。
            int port = server.getListenAddress("triple").getPort();
            ReferenceConfig<WireEcho> reference = new ReferenceConfig<>();
            reference.setInterface(WireEcho.class);
            String endpoint = "tri://127.0.0.1:" + port + "/" + WireEcho.class.getName();
            reference.setUrl(endpoint + "?serialization=hessian2");
            reference.setScope("remote");
            reference.setCheck(false);
            reference.setRetries(0);
            reference.setTimeout(5000);
            reference.setGroup("sdk-basic");
            reference.setVersion("1.0.0");
            ApplicationConfig application = new ApplicationConfig("triple-sdk-basic-client");
            application.setQosEnable(false);
            application.setEnableFileCache(false);
            FrameworkModel sdk = new FrameworkModel();
            try {
                DubboBootstrap bootstrap = DubboBootstrap.newInstance(sdk) //
                        .application(application).registry(new RegistryConfig("N/A"));
                try {
                    bootstrap.reference(reference).start();
                    String expected = "Java SDK triple 中文 🙂";
                    assertEquals(expected, reference.get().echo(expected));
                    assertEquals(1, calls.get());
                } finally {
                    bootstrap.destroy();
                }
            } finally {
                // 装配或应用清理失败时也释放自有框架，不销毁其它测试的共享资源。
                sdk.destroy();
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsExternalJavaSdkServer() throws Exception {
        // SDK 将 0/-1 解释为默认或扫描端口，先由 loopback:0 取得随机候选端口。
        int port;
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        // 标准 mapped-loopback 避免 SDK 将 127.* 规范化为 anyhost，实际只绑定 127.0.0.1。
        protocol.setHost("::ffff:127.0.0.1");
        protocol.setRegister(false);
        ServiceConfig<WireEcho> sdkService = new ServiceConfig<>();
        sdkService.setInterface(WireEcho.class);
        sdkService.setRef(value -> "triple-sdk:" + value);
        sdkService.setProtocol(protocol);
        sdkService.setRegister(false);
        sdkService.setScope("remote");
        sdkService.setSerialization("hessian2");
        sdkService.setGroup("sdk-basic");
        sdkService.setVersion("1.0.0");
        ApplicationConfig application = new ApplicationConfig("triple-sdk-basic-server");
        application.setQosEnable(false);
        application.setEnableFileCache(false);
        FrameworkModel sdk = new FrameworkModel();
        try {
            DubboBootstrap bootstrap = DubboBootstrap.newInstance(sdk) //
                    .application(application).registry(new RegistryConfig("N/A"));
            try {
                // start 返回后读取实际导出端口，消费者不依赖预选端口或固定休眠猜测就绪。
                bootstrap.protocol(protocol).service(sdkService).start();
                int actualPort = sdkService.getExportedUrls().get(0).getPort();
                MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
                configuration.connector("http").option("httpVersion", 2).protocol("triple");
                try (MetContext client = configuration.buildContext()) {
                    client.start();
                    MetBindInfo<WireEcho> service = client.publisher().rsfService(WireEcho.class) //
                            .aliasName("triple", WireEcho.class.getName()).group("sdk-basic").version("1.0.0") //
                            .serialize("Hessian2").register();

                    // 当前 Meteor 容器不发布本地实现，SDK 专属前缀证明请求经过网络服务端。
                    InterAddress address = new InterAddress("triple", "127.0.0.1", actualPort, "default");
                    WireEcho remote = client.getRsfClient(address).getRemote(service);
                    assertEquals("triple-sdk:反向 Java 🙂", remote.echo("反向 Java 🙂"));
                }
            } finally {
                bootstrap.destroy();
            }
        } finally {
            // 装配或应用清理失败时也释放自有框架，不销毁其它测试的共享资源。
            sdk.destroy();
        }
    }
}
