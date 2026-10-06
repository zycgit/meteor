/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.Configuration;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextRpcTest extends BootstrapTestSupport {

    @Test
    public void concurrentAsyncCallsAndCallbacksUseCobbleFutures() throws Exception {
        for (int round = 0; round < 5; round++) {
            MetContext server = this.context(this.configuration()), client = this.context(this.configuration());
            server.publisher().rsfService(Echo.class).to(EchoService.class).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            List<MetFuture> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "call-" + i }));
            }
            for (int i = 0; i < calls.size(); i++) {
                assertEquals("call-" + i, calls.get(i).getData(5, TimeUnit.SECONDS));
            }
            BasicFuture<Object> callback = new BasicFuture<>();
            remote.callbackInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "callback" }, new FutureCallback<Object>() {
                public void completed(Object result) {
                    callback.completed(result);
                }

                public void failed(Throwable failure) {
                    callback.failed(failure);
                }
            });
            assertEquals("callback", callback.get(5, TimeUnit.SECONDS));
            client.close();
            server.close();
        }
    }

    @Test
    public void remoteFailureTimeoutAndOfflineStatusRemainObservable() throws Exception {
        Configuration serverConfig = this.configuration();
        serverConfig.setQueueMinPoolSize(2);
        MetContext server = this.context(serverConfig), client = this.context(this.configuration());
        server.publisher().rsfService(Echo.class).toProvider(EchoService::new).register();
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(250).register();
        server.start();
        client.start();
        MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
        assertTrue(assertFailure(remote.asyncInvoke(service, "fail", new Class<?>[0], new Object[0])) instanceof MetException);
        assertTrue(assertFailure(remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1000 })) instanceof MetTimeoutException);
        server.offline();
        Throwable offline = assertFailure(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "offline" }));
        assertEquals(ProtocolStatus.Forbidden, ((MetException) offline).getStatus());
        server.online();
    }

    @Test
    public void expiredCallIsNotSentWhenHandshakeCompletesLate() throws Exception {
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            MetContext client = this.context(this.configuration());
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(150).register();
            client.start();
            MetClient remote = client.getRsfClient(new InterAddress("rsf://127.0.0.1:" + peer.getLocalPort() + "/default"));
            MetFuture call = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "expired" });
            peer.setSoTimeout(2000);
            try (Socket socket = peer.accept()) {
                socket.setSoTimeout(2000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                byte[] header = new byte[13];
                input.readFully(header);
                int length = ((header[10] & 255) << 16) | ((header[11] & 255) << 8) | (header[12] & 255);
                byte[] body = new byte[length];
                input.readFully(body);
                assertTrue(assertFailure(call) instanceof MetTimeoutException);
                // Echo a valid greeting only after the RPC deadline has expired.
                socket.getOutputStream().write(header);
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
                socket.setSoTimeout(300);
                try {
                    int unexpected = input.read();
                    fail("Expired request reached the socket: " + unexpected);
                } catch (SocketTimeoutException expected) {
                }
            }
        }
    }

    @Test
    public void messageModeCompletesOnAcceptWhileHandlerIsStillRunning() throws Exception {
        MetContext server = this.context(this.configuration()), client = this.context(this.configuration());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        server.publisher().rsfService(Signal.class).toInstance(value -> {
            entered.countDown();
            release.await();
            finished.countDown();
        }).asMessage().register();
        MetBindInfo<Signal> service = client.publisher().rsfService(Signal.class).asMessage().register();
        server.start();
        client.start();
        try {
            MetFuture sent = client.getRsfClient(server.bindAddress("rsf")).asyncInvoke(service, "accept", new Class<?>[] { String.class }, new Object[] { "message" });
            sent.getData(5, TimeUnit.SECONDS);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, finished.getCount());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void realRsfCallsUseAllSerializersAndRegisteredFilters() throws Exception {
        MetContext server = this.context(this.configuration());
        MetContext client = this.context(this.configuration());
        AtomicInteger received = new AtomicInteger();
        server.publisher().bindFilter("count", (request, response, chain) -> {
            received.incrementAndGet();
            chain.doFilter(request, response);
        });
        server.start();
        client.start();
        for (String format : new String[] { "Java", "Json", "Hessian", "Hprose" }) {
            server.publisher().rsfService(Echo.class, EchoService.class).name(format).serialize(format.toLowerCase(Locale.ROOT)).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).name(format).serialize(format.toUpperCase(Locale.ROOT)).register();
            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals(format, "中文🙂", remote.echo("中文🙂"));
            assertEquals("", remote.echo(""));
            assertNull(remote.echo(null));
            assertEquals(5, remote.add(2, 3));
            assertArrayEquals(new int[] { 1, 2, 3 }, remote.array(new int[] { 1, 2, 3 }));
        }
        assertEquals(20, received.get());
        assertEquals(Collections.singleton("rsf"), server.runProtocols());
    }

    @Test
    public void fixedTargetBypassesLocalServiceEvenWhenBothContextsProvideIt() throws Exception {
        MetContext server = this.context(this.configuration());
        MetContext client = this.context(this.configuration());
        server.publisher().rsfService(Echo.class, new EchoService() {
            @Override
            public String echo(String value) {
                return "remote";
            }
        }).register();
        MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class, new EchoService() {
            @Override
            public String echo(String value) {
                return "local";
            }
        }).register();
        server.start();
        client.start();
        assertEquals("local", client.getRsfClient().getRemote(service).echo("value"));
        assertEquals("remote", client.getRsfClient(server.bindAddress("rsf")).getRemote(service).echo("value"));
    }
}
