/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.route.ArgsKey;
import net.hasor.meteor.bootstrap.Configuration;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.MetException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextLifecycleTest extends BootstrapTestSupport {

    @Test
    public void closeDrainsConnectionsAndCompletesOutstandingCalls() throws Exception {
        MetContext server = this.context(this.configuration()), client = this.context(this.configuration());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
            @Override
            public String slow(int millis) throws InterruptedException {
                entered.countDown();
                release.await();
                return "done";
            }
        }).register();
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        server.start();
        client.start();
        MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
        try {
            MetFuture call = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.close();
            assertTrue("Close must finish pending calls before returning", call.isDone());
            Throwable failure = assertFailure(call);
            assertTrue("Unexpected close failure: " + failure, failure instanceof IOException || failure instanceof IllegalStateException);
            assertFailure(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "closed" }));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void failedBindRollsBackNetworkResources() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            MetContext runtime = this.context(this.configuration(occupied.getLocalPort()));
            try {
                runtime.start();
                fail();
            } catch (IllegalStateException expected) {
            }
            assertFalse(runtime.isOnline());
            assertTrue(runtime.runProtocols().isEmpty());
        }
    }

    @Test
    public void invalidAddressMaintenanceConfigurationRollsBackNetworkStartup() throws Exception {
        Configuration config = this.configuration();
        config.setRefreshCacheTime(0);
        MetContext runtime = this.context(config);
        try {
            runtime.start();
            fail("Invalid address maintenance interval must fail startup");
        } catch (IllegalStateException failure) {
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
        assertFalse(runtime.isOnline());
        assertTrue(runtime.runProtocols().isEmpty());
    }

    @Test
    public void manualOnlineStateControlsRemoteAdmissionAndStartIsIdempotent() throws Exception {
        Configuration config = this.configuration();
        config.setAutomaticOnline(false);
        MetContext server = this.context(config);
        MetContext client = this.context(this.configuration());
        server.publisher().rsfService(Echo.class, new EchoService()).register();
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        server.start();
        client.start();
        Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
        assertFalse(server.isOnline());
        MetFuture denied = client.getRsfClient(server.bindAddress("rsf")).asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "offline" });
        Throwable failure = assertThrows(ExecutionException.class, () -> denied.getData(5, TimeUnit.SECONDS)).getCause();
        assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());
        server.online();
        server.online();
        assertEquals("online", remote.echo("online"));
        server.offline();
        server.start();
        assertFalse("Repeated start must not undo explicit offline", server.isOnline());
        server.close();
        server.close();
        assertNull(server.bindAddress("rsf"));
        assertTrue(server.runProtocols().isEmpty());
        assertThrows(IllegalStateException.class, server::start);
        assertThrows(IllegalStateException.class, server::online);
    }

    @Test
    public void invalidSpiIsRejectedBeforeAnyListenerIsOpened() throws Exception {
        Configuration config = this.configuration();
        int firstPort = config.getBindAddressSet("rsf").getPort();
        config.connector("missing-factory").protocol("hprose");
        config.setLocalDiskCache(true);
        config.setDataHome(this.temporary.newFolder().toPath());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.context(config));
        assertTrue(failure.getMessage().contains("missing-factory"));
        try (ServerSocket rebound = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(firstPort, rebound.getLocalPort());
        }
    }

    @Test
    public void closeBeforeStartAndConstructorFailureReleaseResources() throws Exception {
        MetContext context = this.context(this.configuration());
        context.close();
        context.close();
        assertThrows(IllegalStateException.class, context::start);
        Configuration config = this.configuration();
        config.setQueueMaxSize(0);
        assertThrows(IllegalArgumentException.class, () -> this.context(config));
    }

    @Test
    public void invalidArgsKeyFailsDuringConstruction() throws Exception {
        Configuration invalidRoute = this.configuration();
        invalidRoute.setArgsKey(ArgsKey.class);
        assertThrows(IllegalArgumentException.class, () -> this.context(invalidRoute));
    }
}
