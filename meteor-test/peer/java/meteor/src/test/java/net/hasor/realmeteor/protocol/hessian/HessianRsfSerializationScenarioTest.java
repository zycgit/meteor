/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.hessian.HessianRsfValueService;
import net.hasor.example.protocol.hessian.HessianRsfValueServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianRsfSerializationScenarioTest {
    private final Set<Thread> originalThreads = Thread.getAllStackTraces().keySet();

    @Test(timeout = 15000)
    public void rsfWithHessianPreservesUnicode() throws Exception {
        // 准备 RSF 两端，通过 Hessian 序列化验证中文和补充平面字符。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hessian。
            String format = "Hessian";
            var published = server.publisher() //
                    .rsfService(HessianRsfValueService.class, HessianRsfValueServiceImpl.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(HessianRsfValueService.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<HessianRsfValueService> service = subscribed.register();

            // 验证中文和补充平面字符往返保留，并且服务只收到一次调用。
            HessianRsfValueService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test(timeout = 15000)
    public void rsfWithHessianPreservesEmptyString() throws Exception {
        // 准备 RSF 两端，通过 Hessian 序列化验证空字符串。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hessian。
            String format = "Hessian";
            var published = server.publisher() //
                    .rsfService(HessianRsfValueService.class, HessianRsfValueServiceImpl.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(HessianRsfValueService.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<HessianRsfValueService> service = subscribed.register();

            // 验证空字符串往返保留，并且服务只收到一次调用。
            HessianRsfValueService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("", remote.echo(""));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test(timeout = 15000)
    public void rsfWithHessianPreservesNull() throws Exception {
        // 准备 RSF 两端，通过 Hessian 序列化验证 null。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hessian。
            String format = "Hessian";
            var published = server.publisher() //
                    .rsfService(HessianRsfValueService.class, HessianRsfValueServiceImpl.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(HessianRsfValueService.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<HessianRsfValueService> service = subscribed.register();

            // 验证 null 往返保留，并且服务只收到一次调用。
            HessianRsfValueService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertNull(remote.echo(null));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test(timeout = 15000)
    public void rsfWithHessianPreservesPrimitiveParameters() throws Exception {
        // 准备 RSF 两端，通过 Hessian 序列化验证基本类型参数。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hessian。
            String format = "Hessian";
            var published = server.publisher() //
                    .rsfService(HessianRsfValueService.class, HessianRsfValueServiceImpl.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(HessianRsfValueService.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<HessianRsfValueService> service = subscribed.register();

            // 验证基本类型参数往返保留，并且服务只收到一次调用。
            HessianRsfValueService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals(5, remote.add(2, 3));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test(timeout = 15000)
    public void rsfWithHessianPreservesArray() throws Exception {
        // 准备 RSF 两端，通过 Hessian 序列化验证数组内容。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hessian。
            String format = "Hessian";
            var published = server.publisher() //
                    .rsfService(HessianRsfValueService.class, HessianRsfValueServiceImpl.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(HessianRsfValueService.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<HessianRsfValueService> service = subscribed.register();

            // 验证数组内容往返保留，并且服务只收到一次调用。
            HessianRsfValueService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertArrayEquals(new int[] { 1, 2, 3 }, remote.array(new int[] { 1, 2, 3 }));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    @After
    public void contextsReleaseTheirFrameworkThreads() throws Exception {
        // 沿用迁移前的生命周期检查，有界等待当前场景创建的框架线程结束。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> remaining = new ArrayList<>();
        do {
            remaining.clear();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                boolean frameworkThread = name.startsWith("RSF") || name.startsWith("Meteor");
                boolean newThread = !this.originalThreads.contains(thread);
                if (newThread && thread.isAlive() && frameworkThread) {
                    remaining.add(name);
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);

        // 超过等待时间的存活线程应保留名称，便于定位资源未释放的原因。
        assertTrue("Meteor threads leaked: " + remaining, remaining.isEmpty());
    }
}
