/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.CacheAddressPool;
import net.hasor.meteor.bootstrap.Configuration;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextAddressTest extends BootstrapTestSupport {

    @Test
    public void configuredAddressPoolRoutesWithoutExplicitTarget() throws Exception {
        MetContext server = this.context(this.configuration()), client = this.context(this.configuration());
        server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();
        server.start();
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).bindAddress(server.bindAddress("rsf")).register();
        client.start();
        assertEquals("routed", client.getRsfClient().getRemote(service).echo("routed"));
    }

    @Test
    public void liveRpcUsesAddressComponentScriptsAndConfiguredArgsKey() throws Exception {
        MetContext first = this.context(this.configuration()), second = this.context(this.configuration());
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
        Configuration config = this.configuration();
        config.setArgsKey(RoutingArgsKey.class);
        MetContext client = this.context(config);
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        String id = service.getBindID();
        client.getUpdater().appendAddress(id, Arrays.asList(first.bindAddress("rsf"), second.bindAddress("rsf")));
        String selector = "addresses.find { it.endsWith(':" + second.bindAddress("rsf").getPort() + "') }";
        assertTrue(client.getUpdater().updateServiceRoute(id, "def evalAddress(id, addresses) { return [" + selector + "] }"));
        client.start();
        Echo remote = client.getRsfClient().getRemote(service);
        assertEquals("second", remote.echo("any"));
        selector = "addresses.find { it.endsWith(':" + first.bindAddress("rsf").getPort() + "') }";
        assertTrue(client.getUpdater().updateArgsRoute(id, "def evalAddress(id, addresses) { return [echo: [special: [" + selector + "]]] }"));
        assertEquals("first", remote.echo("special"));
        assertEquals("second", remote.echo("other"));
    }

    @Test
    public void updaterAndPublisherUseTheSameAddressPool() throws Throwable {
        MetContext runtime = this.context(this.configuration());
        InterAddress fixed = new InterAddress("rsf://127.0.0.1:2211/default");
        InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2212/default");
        MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).bindAddress(fixed).register();
        String id = service.getBindID();
        MetUpdater updater = runtime.getUpdater();
        updater.appendAddress(id, Collections.singletonList(dynamic));
        AddressPool pool = ((CacheAddressPool) updater);
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

    @Test
    public void enabledDiskCacheRestoresComponentSnapshotAndRoutesRealRpc() throws Exception {
        MetContext server = this.context(this.configuration());
        server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();
        server.start();
        File home = this.temporary.newFolder();
        MetContext seed = this.context(this.configuration());
        MetBindInfo<Echo> seedService = seed.publisher().rsfService(Echo.class).register();
        seed.getUpdater().appendAddress(seedService.getBindID(), Collections.singletonList(server.bindAddress("rsf")));
        try (DiskCache cache = new DiskCache((AddressPool) seed.getUpdater(), home, 60000, 3600000)) {
            cache.storeConfig();
        }
        seed.close();
        Configuration config = this.configuration();
        config.setDataHome(home.toPath());
        config.setLocalDiskCache(true);
        MetContext client = this.context(config);
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        client.start();
        assertEquals(Collections.singletonList(server.bindAddress("rsf")), client.getUpdater().queryAllAddresses(service.getBindID()));
        assertEquals("restored", client.getRsfClient().getRemote(service).echo("restored"));
    }

    @Test
    public void disabledDiskCacheDoesNotReadOrCreateSnapshots() throws Exception {
        File home = this.temporary.newFolder();
        Configuration config = this.configuration();
        config.setDataHome(home.toPath());
        MetContext runtime = this.context(config);
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
        MetContext restarted = this.context(config);
        restarted.publisher().rsfService(Echo.class).register();
        restarted.start();
        assertTrue(restarted.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
    }

    @Test
    public void updaterAndPublisherUseSamePoolAndMaintenanceRevivesInvalidAddresses() throws Exception {
        File home = this.temporary.newFolder();
        Configuration source = this.configuration();
        source.setDataHome(home.toPath());
        source.setRefreshCacheTime(20);
        MetContext client = this.context(source);
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
        client.getUpdater().appendAddress(service.getBindID(), Collections.singletonList(address));
        // Set a short retry deadline through the address component's public API.
        ((CacheAddressPool) client.getUpdater()).getBucket(service.getBindID()).invalidAddress(address, 300);
        assertEquals(Collections.singletonList(address), client.getUpdater().queryInvalidAddresses(service.getBindID()));
        client.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Collections.singletonList(address), client.getUpdater().queryAvailableAddresses(service.getBindID()));
        client.close();
        assertEquals(0, home.list().length);
    }

}
