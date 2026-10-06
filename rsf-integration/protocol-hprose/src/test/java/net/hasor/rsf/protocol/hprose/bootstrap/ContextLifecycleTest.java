/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfClient;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfFuture;
import net.hasor.rsf.address.route.ArgsKey;
import net.hasor.rsf.bootstrap.Configuration;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextLifecycleTest extends BootstrapTestSupport {

    @Test
    public void closeDrainsConnectionsAndCompletesOutstandingCalls() throws Exception {
        RsfContext server = this.context(this.configuration()), client = this.context(this.configuration());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
            @Override
            public String slow(int millis) throws InterruptedException {
                entered.countDown();
                release.await();
                return "done";
            }
        }).register();
        RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        server.start();
        client.start();
        RsfClient remote = client.getRsfClient(server.bindAddress("rsf"));
        try {
            RsfFuture call = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
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
            RsfContext runtime = this.context(this.configuration(occupied.getLocalPort()));
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
        RsfContext runtime = this.context(config);
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
        RsfContext server = this.context(config);
        RsfContext client = this.context(this.configuration());
        server.publisher().rsfService(Echo.class, new EchoService()).register();
        RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
        server.start();
        client.start();
        Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
        assertFalse(server.isOnline());
        RsfFuture denied = client.getRsfClient(server.bindAddress("rsf")).asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "offline" });
        Throwable failure = assertThrows(ExecutionException.class, () -> denied.getData(5, TimeUnit.SECONDS)).getCause();
        assertEquals(ProtocolStatus.Forbidden, ((RsfException) failure).getStatus());
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
        RsfContext context = this.context(this.configuration());
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
