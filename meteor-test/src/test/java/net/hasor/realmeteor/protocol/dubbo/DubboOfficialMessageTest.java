/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方二进制 oneway 只确认本地写出，业务完成由测试独立观察。 */
public class DubboOfficialMessageTest {
    private static final String SERVICE = DubboInteropService.class.getName();

    @Test(timeout = 60000)
    public void officialDubbo3ClientSendsOnewayToMeteor() throws Exception {
        // 共享业务实现保留自己的计数，额外完成信号仅用于等待实际业务执行。
        CountDownLatch delivered = new CountDownLatch(1);
        DubboInteropServiceImpl implementation = new DubboInteropServiceImpl() {
            @Override
            public void notice(String value) {
                super.notice(value);
                delivered.countDown();
            }
        };
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通接口，由客户端决定该次调用是否为 oneway。
            server.start();
            server.publisher().rsfService(DubboInteropService.class, implementation) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 配置 return=false、sent=true，RESULT 只说明其本地发送完成。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=oneway")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());

                // 独立等待应用的执行信号，不把 write 完成解释成远端业务回执。
                assertTrue(delivered.await(3, TimeUnit.SECONDS));
                assertEquals(1, implementation.notices());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorSendsOnewayToOfficialDubbo3Provider() throws Exception {
        // 独立官方 JVM 发布共享业务实现，计数只能通过普通业务查询读取。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 两份本地绑定共用相同 wire 身份，只有通知绑定配置为消息模式。
                client.start();
                MetBindInfo<DubboInteropService> notification = client.publisher() //
                        .rsfService(DubboInteropService.class).name("notification") //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").asMessage().register();
                MetBindInfo<DubboInteropService> observer = client.publisher() //
                        .rsfService(DubboInteropService.class).name("observer") //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", "127.0.0.1", port, "default");
                MetClient caller = client.getRsfClient(address);

                // 消息 Future 仅在完整写出后完成，不等待远端返回 Dubbo 响应帧。
                MetFuture written = caller.asyncInvoke(notification, "notice", new Class<?>[] { String.class }, //
                        new Object[] { "notification" });
                MetResponse accepted = written.get(5, TimeUnit.SECONDS);
                assertTrue(written.getRequest().isMessage());
                assertNull(accepted.getData());

                // 普通查询只是测试观察业务计数，不是 oneway 的协议 ACK 或完成条件。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int count;
                do {
                    long remaining = deadline - System.nanoTime();
                    assertTrue("Official provider did not execute the notification", remaining > 0);
                    MetFuture observed = caller.asyncInvoke(observer, "notices", new Class<?>[0], new Object[0]);
                    count = (Integer) observed.getData(remaining, TimeUnit.NANOSECONDS);
                    assertFalse(observed.getRequest().isMessage());
                } while (count == 0);
                assertEquals(1, count);
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientSendsOnewayToMeteor() throws Exception {
        // 共享业务实现保留自己的计数，额外完成信号仅用于等待实际业务执行。
        CountDownLatch delivered = new CountDownLatch(1);
        DubboInteropServiceImpl implementation = new DubboInteropServiceImpl() {
            @Override
            public void notice(String value) {
                super.notice(value);
                delivered.countDown();
            }
        };
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通接口，由客户端决定该次调用是否为 oneway。
            server.start();
            server.publisher().rsfService(DubboInteropService.class, implementation) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 配置 return=false、sent=true，RESULT 只说明其本地发送完成。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=oneway")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());

                // 独立等待应用的执行信号，不把 write 完成解释成远端业务回执。
                assertTrue(delivered.await(3, TimeUnit.SECONDS));
                assertEquals(1, implementation.notices());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorSendsOnewayToOfficialDubbo2Provider() throws Exception {
        // 独立官方 JVM 发布共享业务实现，计数只能通过普通业务查询读取。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 两份本地绑定共用相同 wire 身份，只有通知绑定配置为消息模式。
                client.start();
                MetBindInfo<DubboInteropService> notification = client.publisher() //
                        .rsfService(DubboInteropService.class).name("notification") //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").asMessage().register();
                MetBindInfo<DubboInteropService> observer = client.publisher() //
                        .rsfService(DubboInteropService.class).name("observer") //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", "127.0.0.1", port, "default");
                MetClient caller = client.getRsfClient(address);

                // 消息 Future 仅在完整写出后完成，不等待远端返回 Dubbo 响应帧。
                MetFuture written = caller.asyncInvoke(notification, "notice", new Class<?>[] { String.class }, //
                        new Object[] { "notification" });
                MetResponse accepted = written.get(5, TimeUnit.SECONDS);
                assertTrue(written.getRequest().isMessage());
                assertNull(accepted.getData());

                // 普通查询只是测试观察业务计数，不是 oneway 的协议 ACK 或完成条件。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int count;
                do {
                    long remaining = deadline - System.nanoTime();
                    assertTrue("Official provider did not execute the notification", remaining > 0);
                    MetFuture observed = caller.asyncInvoke(observer, "notices", new Class<?>[0], new Object[0]);
                    count = (Integer) observed.getData(remaining, TimeUnit.NANOSECONDS);
                    assertFalse(observed.getRequest().isMessage());
                } while (count == 0);
                assertEquals(1, count);
            }
        }
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var connector = configuration.connector("tcp");
        if (listen) {
            // 原生 SDK 可能将 loopback 规范化成本机地址，监听所有本机接口保持可达。
            connector.bind("0.0.0.0", 0);
        }
        connector.protocol("dubbo");
        return configuration;
    }
}
