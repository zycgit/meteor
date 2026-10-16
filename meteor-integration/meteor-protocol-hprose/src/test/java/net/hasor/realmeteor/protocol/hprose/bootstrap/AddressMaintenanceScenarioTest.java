/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.CacheAddressPool;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AddressMaintenanceScenarioTest extends BootstrapTestSupport {
    @Test
    public void maintenanceRevivesAnInvalidDynamicAddress() throws Exception {
        // 准备带短刷新周期的运行环境和一条临时失效地址。
        File home = this.temporary.newFolder();
        MetConfiguration configuration = this.configuration();
        configuration.setDataHome(home.toPath());
        configuration.setRefreshCacheTime(20);
        try (MetContext client = configuration.buildContext()) {
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(service.getBindID(), Collections.singletonList(address));
            String serviceId = service.getBindID();
            CacheAddressPool pool = (CacheAddressPool) updater;
            pool.getBucket(serviceId).invalidAddress(address, 300);
            List<InterAddress> expected = Collections.singletonList(address);
            assertEquals(expected, updater.queryInvalidAddresses(serviceId));

            // 启动维护任务并有界等待地址自动恢复。
            client.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (updater.queryAvailableAddresses(serviceId).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expected, updater.queryAvailableAddresses(serviceId));

        }
    }

    @Test
    public void enabledDiskCacheRestoresSnapshotAndRoutesRealRpc() throws Exception {
        // 启动真实服务端，并把它的地址保存到独立磁盘快照。
        MetConfiguration serverConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext()) {
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService()) //
                    .register();
            server.start();

            File home = this.temporary.newFolder();
            List<InterAddress> addresses = Collections.singletonList(server.getListenAddress("rsf"));
            MetConfiguration seedConfiguration = this.configuration();
            try (MetContext seed = seedConfiguration.buildContext()) {
                MetBindInfo<Echo> seedService = seed.publisher().rsfService(Echo.class).register();
                seed.getUpdater().appendAddress(seedService.getBindID(), addresses);
                AddressPool seedPool = (AddressPool) seed.getUpdater();
                try (DiskCache cache = new DiskCache(seedPool, home, 60000, 3600000)) {
                    cache.storeConfig();
                }
            }

            // 全新客户端从磁盘恢复地址，并通过恢复结果完成真实 RPC。
            MetConfiguration clientConfiguration = this.configuration();
            clientConfiguration.setDataHome(home.toPath());
            clientConfiguration.setLocalDiskCache(true);
            try (MetContext client = clientConfiguration.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                client.start();
                List<InterAddress> restored = client.getUpdater().queryAllAddresses(service.getBindID());
                assertEquals(addresses, restored);
                assertEquals("restored", client.getRsfClient().getRemote(service).echo("restored"));
            }
        }
    }

    @Test
    public void disabledDiskCacheIgnoresExistingSnapshot() throws Exception {
        // 创建与服务标识对应的有效地址快照。
        File home = this.temporary.newFolder();
        MetConfiguration configuration = this.configuration();
        configuration.setDataHome(home.toPath());
        String serviceId;
        try (MetContext runtime = configuration.buildContext()) {
            MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).register();
            serviceId = service.getBindID();
        }
        try (CacheAddressPool seed = new CacheAddressPool(configuration); //
             DiskCache cache = new DiskCache(seed, home, 60000, 3600000)) {
            seed.appendAddress(serviceId, new InterAddress("rsf://127.0.0.1:2211/default"));
            cache.storeConfig();
        }
        assertTrue(new File(home, "snapshot/address.index").isFile());

        // 保持磁盘缓存关闭时，重启上下文也不能读取已有快照。
        MetConfiguration restartConfiguration = this.configuration();
        restartConfiguration.setDataHome(home.toPath());
        try (MetContext restarted = restartConfiguration.buildContext()) {
            restarted.publisher().rsfService(Echo.class).register();
            restarted.start();
            assertTrue(restarted.getUpdater().queryAllAddresses(serviceId).isEmpty());
        }
    }
}
