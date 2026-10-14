/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit.bootstrap;
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
import net.hasor.meteor.protocol.hprose.scenario.bootstrap.BootstrapTestSupport;
import net.hasor.meteor.protocol.hprose.scenario.example.Echo;
import net.hasor.meteor.protocol.hprose.scenario.example.EchoService;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressMaintenanceTest extends BootstrapTestSupport {
    @Test
    public void fixedAddressesRemainAvailableWhenMarkedInvalid() throws Exception {
        // 静态绑定地址与动态地址共享地址本，失效处理遵循各自规则。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
            MetBindInfo<Echo> service = runtime.publisher() //
                    .rsfService(Echo.class) //
                    .bindAddress(fixed) //
                    .register();
            String id = service.getBindID();
            MetUpdater updater = runtime.getUpdater();
            updater.appendAddress(id, Collections.singletonList(dynamic));

            // 标记固定地址失效后，它仍应保留在可用集合中。
            AddressPool pool = (CacheAddressPool) updater;
            pool.invalidAddress(fixed);
            assertTrue(updater.queryAvailableAddresses(id).contains(fixed));
            assertFalse(updater.queryInvalidAddresses(id).contains(fixed));
        }
    }

    @Test
    public void dynamicAddressesCanBeMarkedInvalid() throws Exception {
        // 静态绑定地址与动态地址共享地址本，失效处理遵循各自规则。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
            MetBindInfo<Echo> service = runtime.publisher() //
                    .rsfService(Echo.class) //
                    .bindAddress(fixed) //
                    .register();
            String id = service.getBindID();
            MetUpdater updater = runtime.getUpdater();
            updater.appendAddress(id, Collections.singletonList(dynamic));

            // 仅动态地址进入失效集合，不影响同一服务的固定地址。
            AddressPool pool = (CacheAddressPool) updater;
            pool.invalidAddress(dynamic);
            assertEquals(Collections.singletonList(fixed), updater.queryAvailableAddresses(id));
            assertEquals(Collections.singletonList(dynamic), updater.queryInvalidAddresses(id));
        }
    }

    @Test
    public void reappendingDynamicAddressRevivesIt() throws Exception {
        // 静态绑定地址与动态地址共享地址本，失效处理遵循各自规则。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
            MetBindInfo<Echo> service = runtime.publisher() //
                    .rsfService(Echo.class) //
                    .bindAddress(fixed) //
                    .register();
            String id = service.getBindID();
            MetUpdater updater = runtime.getUpdater();
            updater.appendAddress(id, Collections.singletonList(dynamic));
            AddressPool pool = (CacheAddressPool) updater;
            pool.invalidAddress(dynamic);

            // 再次收到相同动态地址时恢复它，失效标记同时清除。
            updater.appendAddress(id, Collections.singletonList(dynamic));
            assertEquals(2, updater.queryAvailableAddresses(id).size());
            assertTrue(updater.queryInvalidAddresses(id).isEmpty());
        }
    }

    @Test
    public void removingBucketClearsItsLookup() throws Throwable {
        // 静态绑定地址与动态地址共享地址本，失效处理遵循各自规则。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
            MetBindInfo<Echo> service = runtime.publisher() //
                    .rsfService(Echo.class) //
                    .bindAddress(fixed) //
                    .register();
            String id = service.getBindID();
            MetUpdater updater = runtime.getUpdater();
            updater.appendAddress(id, Collections.singletonList(dynamic));

            // 删除整个服务地址桶后，查询和选址均不再命中。
            AddressPool pool = (CacheAddressPool) updater;
            assertTrue(pool.removeBucket(id));
            assertNull(pool.getBucket(id));
            assertNull(pool.nextAddress(id, "echo", new Object[0]));
        }
    }

    @Test
    public void maintenanceRevivesAnInvalidDynamicAddress() throws Exception {
        // 短暂失效的动态地址由维护任务恢复，无需手动拉取。
        File home = this.temporary.newFolder();
        MetConfiguration source = this.configuration();
        source.setDataHome(home.toPath());
        source.setRefreshCacheTime(20);
        try (MetContext client = source.buildContext()) {
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(service.getBindID(), Collections.singletonList(address));

            // 通过地址本公开能力缩短重试周期，随后只观察维护任务自动恢复。
            String serviceId = service.getBindID();
            CacheAddressPool pool = (CacheAddressPool) updater;
            pool.getBucket(serviceId).invalidAddress(address, 300);
            List<InterAddress> expected = Collections.singletonList(address);
            assertEquals(expected, updater.queryInvalidAddresses(serviceId));

            // 启动维护任务并有界等待地址恢复，不主动修改地址状态。
            client.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (updater.queryAvailableAddresses(serviceId).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expected, updater.queryAvailableAddresses(serviceId));

            // 未启用磁盘缓存，关闭后不应留下快照文件。
            client.close();
            assertEquals(0, home.list().length);
        }
    }

    @Test
    public void enabledDiskCacheRestoresComponentSnapshotAndRoutesRealRpc() throws Exception {
        // 保存地址快照，随后用全新上下文恢复并完成真实调用。
        MetConfiguration serverConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext()) {
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService()) //
                    .register();

            server.start();
            File home = this.temporary.newFolder();
            List<InterAddress> addresses = Collections.singletonList(server.bindAddress("rsf"));
            MetConfiguration seedConfiguration = this.configuration();
            try (MetContext seed = seedConfiguration.buildContext()) {
                MetBindInfo<Echo> seedService = seed.publisher().rsfService(Echo.class).register();
                seed.getUpdater().appendAddress(seedService.getBindID(), addresses);
                AddressPool seedPool = (AddressPool) seed.getUpdater();
                try (DiskCache cache = new DiskCache(seedPool, home, 60000, 3600000)) {
                    cache.storeConfig();
                }
            }

            // 原上下文完全退出后，再从磁盘恢复地址并调用仍在线的服务端。
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

    @Test
    public void disabledDiskCacheDoesNotLoadAnExistingSnapshot() throws Exception {
        // 先取得服务标识，磁盘快照与重启后的服务使用同一个地址桶。
        File home = this.temporary.newFolder();
        MetConfiguration config = this.configuration();
        config.setDataHome(home.toPath());
        String serviceId;
        try (MetContext runtime = config.buildContext()) {
            MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).register();
            serviceId = service.getBindID();
        }

        // 独立创建一份有效快照，随后验证禁用缓存时也不会读取它。
        try (CacheAddressPool seed = new CacheAddressPool(config); //
             DiskCache cache = new DiskCache(seed, home, 60000, 3600000)) {
            seed.appendAddress(serviceId, new InterAddress("rsf://127.0.0.1:2211/default"));
            cache.storeConfig();
        }
        assertTrue(new File(home, "snapshot/address.index").isFile());

        // 使用默认关闭缓存的新配置，已有文件不能参与地址恢复。
        MetConfiguration restartConfig = this.configuration();
        restartConfig.setDataHome(home.toPath());
        try (MetContext restarted = restartConfig.buildContext()) {
            restarted.publisher().rsfService(Echo.class).register();
            restarted.start();
            assertTrue(restarted.getUpdater().queryAllAddresses(serviceId).isEmpty());
        }
    }
}
