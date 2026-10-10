/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.CacheAddressPool;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextAddressTest extends BootstrapTestSupport {

    @Test
    public void configuredAddressPoolRoutesWithoutExplicitTarget() throws Exception {
        // 只配置服务地址本，通过正常客户端入口完成远程调用。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            server.start();
            InterAddress target = server.bindAddress("rsf");
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).bindAddress(target).register();
            client.start();
            assertEquals("routed", client.getRsfClient().getRemote(service).echo("routed"));
        }
    }

    @Test
    public void liveRpcUsesAddressComponentScriptsAndConfiguredArgsKey() throws Exception {
        // 两个服务端返回不同标记，观察服务路由与参数路由的实际选择。
        MetConfiguration firstConfiguration = this.configuration();
        MetConfiguration secondConfiguration = this.configuration();
        try (MetContext first = firstConfiguration.buildContext(); //
             MetContext second = secondConfiguration.buildContext()) {
            first.publisher().rsfService(Echo.class).toInstance(new EchoService() {
                @Override
                public String echo(String value) {
                    return "first";
                }
            }).register();
            second.publisher().rsfService(Echo.class).toInstance(new EchoService() {
                @Override
                public String echo(String value) {
                    return "second";
                }
            }).register();

            first.start();
            second.start();
            MetConfiguration config = this.configuration();
            config.setArgsKey(RoutingArgsKey.class);
            try (MetContext client = config.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                String id = service.getBindID();
                List<InterAddress> providers = List.of(first.bindAddress("rsf"), second.bindAddress("rsf"));
                client.getUpdater().appendAddress(id, providers);
                String selector = "addresses.find { it.endsWith(':" + second.bindAddress("rsf").getPort() + "') }";
                String serviceRule = "def evalAddress(id, addresses) { return [" + selector + "] }";
                assertTrue(client.getUpdater().updateServiceRoute(id, serviceRule));
                client.start();

                Echo remote = client.getRsfClient().getRemote(service);
                assertEquals("second", remote.echo("any"));

                // 特定参数改走第一个实例，其他参数仍遵守服务路由。
                selector = "addresses.find { it.endsWith(':" + first.bindAddress("rsf").getPort() + "') }";
                String argsRule = "def evalAddress(id, addresses) { return [echo: [special: [" + selector + "]]] }";
                assertTrue(client.getUpdater().updateArgsRoute(id, argsRule));
                assertEquals("first", remote.echo("special"));
                assertEquals("second", remote.echo("other"));
            }
        }
    }

    @Test
    public void updaterAndPublisherUseTheSameAddressPool() throws Throwable {
        // 静态绑定地址与动态地址共享地址本，失效处理遵循各自规则。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
            MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).bindAddress(fixed).register();
            String id = service.getBindID();
            MetUpdater updater = runtime.getUpdater();
            updater.appendAddress(id, Collections.singletonList(dynamic));
            AddressPool pool = (CacheAddressPool) updater;
            pool.invalidAddress(fixed);
            pool.invalidAddress(dynamic);
            assertEquals(Collections.singletonList(fixed), updater.queryAvailableAddresses(id));
            assertEquals(Collections.singletonList(dynamic), updater.queryInvalidAddresses(id));
            updater.appendAddress(id, Collections.singletonList(dynamic));
            assertEquals(2, updater.queryAvailableAddresses(id).size());
            assertTrue(pool.removeBucket(id));
            assertNull(pool.getBucket(id));
            assertNull(pool.nextAddress(id, "echo", new Object[0]));
        }
    }

    @Test
    public void enabledDiskCacheRestoresComponentSnapshotAndRoutesRealRpc() throws Exception {
        // 保存地址快照，随后用全新上下文恢复并完成真实调用。
        MetConfiguration serverConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext()) {
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            server.start();
            File home = this.temporary.newFolder();
            MetConfiguration seedConfiguration = this.configuration();
            try (MetContext seed = seedConfiguration.buildContext()) {
                MetBindInfo<Echo> seedService = seed.publisher().rsfService(Echo.class).register();
                List<InterAddress> addresses = Collections.singletonList(server.bindAddress("rsf"));
                seed.getUpdater().appendAddress(seedService.getBindID(), addresses);
                try (DiskCache cache = new DiskCache((AddressPool) seed.getUpdater(), home, 60000, 3600000)) {
                    cache.storeConfig();
                }
                seed.close();
                MetConfiguration config = this.configuration();
                config.setDataHome(home.toPath());
                config.setLocalDiskCache(true);
                try (MetContext client = config.buildContext()) {
                    MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                    client.start();
                    List<InterAddress> restored = client.getUpdater().queryAllAddresses(service.getBindID());
                    assertEquals(addresses, restored);
                    assertEquals("restored", client.getRsfClient().getRemote(service).echo("restored"));
                }
            }
        }
    }

    @Test
    public void disabledDiskCacheDoesNotReadOrCreateSnapshots() throws Exception {
        // 禁用缓存时不创建文件，也不读取已有的快照。
        File home = this.temporary.newFolder();
        MetConfiguration config = this.configuration();
        config.setDataHome(home.toPath());
        try (MetContext runtime = config.buildContext()) {
            MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).register();

            runtime.start();

            runtime.close();
            assertTrue(runtime.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
            assertEquals(0, home.list().length);

            CacheAddressPool seed = new CacheAddressPool(runtime.getSettings());
            seed.appendAddress(service.getBindID(), new InterAddress("rsf://127.0.0.1:2211/default"));
            try (DiskCache cache = new DiskCache(seed, home, 60000, 3600000)) {
                cache.storeConfig();
            }
            assertTrue(new File(home, "snapshot/address.index").isFile());
            config = this.configuration();
            config.setDataHome(home.toPath());
            try (MetContext restarted = config.buildContext()) {
                restarted.publisher().rsfService(Echo.class).register();
                restarted.start();
                assertTrue(restarted.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
            }
        }
    }

    @Test
    public void updaterAndPublisherUseSamePoolAndMaintenanceRevivesInvalidAddresses() throws Exception {
        // 短暂失效的动态地址由维护任务恢复，无需手动拉取。
        File home = this.temporary.newFolder();
        MetConfiguration source = this.configuration();
        source.setDataHome(home.toPath());
        source.setRefreshCacheTime(20);
        try (MetContext client = source.buildContext()) {
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
            client.getUpdater().appendAddress(service.getBindID(), Collections.singletonList(address));
            // 通过地址本公开能力缩短重试周期，随后只观察维护任务自动恢复。
            String serviceId = service.getBindID();
            CacheAddressPool pool = (CacheAddressPool) client.getUpdater();
            pool.getBucket(serviceId).invalidAddress(address, 300);
            List<InterAddress> expected = Collections.singletonList(address);
            assertEquals(expected, client.getUpdater().queryInvalidAddresses(serviceId));

            client.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.getUpdater().queryAvailableAddresses(serviceId).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expected, client.getUpdater().queryAvailableAddresses(serviceId));

            client.close();
            assertEquals(0, home.list().length);
        }
    }

}
