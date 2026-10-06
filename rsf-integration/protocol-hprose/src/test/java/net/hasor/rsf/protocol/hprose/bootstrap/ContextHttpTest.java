/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Supplier;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfClient;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfFuture;
import net.hasor.rsf.bootstrap.Configuration;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.RsfTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextHttpTest extends BootstrapTestSupport {

    @Test
    public void hproseHttpRequestReachesRegisteredServiceWithoutHasor() throws Exception {
        Configuration config = this.configuration();
        int httpPort = this.port();
        config.connector("http").bind("127.0.0.1", httpPort).protocol("hprose").contextPath("/hprose");
        RsfContext server = this.context(config);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();
        server.start();
        assertEquals("HTTP中文", this.invokeHttp(httpPort, "/hprose", "HTTP中文"));
    }

    @Test
    public void mountedProtocolsShareConfiguredPortAndAdvertiseTheirOwnSchemes() throws Exception {
        Configuration config = this.configuration();
        int sharedPort = this.port();
        Configuration.ConnectorSettings http = config.connector("http").bind("127.0.0.1", sharedPort);
        http.protocol("hprosea").contextPath("/a");
        http.protocol("hproseb").contextPath("/b");
        RsfContext server = this.context(config);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();
        server.start();
        for (String branch : Arrays.asList("a", "b")) {
            String scheme = "hprose" + branch;
            assertTrue(server.runProtocols().contains(scheme));
            assertEquals(scheme, server.bindAddress(scheme).getSchema());
            assertEquals(sharedPort, server.bindAddress(scheme).getPort());
            assertEquals(scheme, this.invokeHttp(sharedPort, "/" + branch, scheme));
        }
    }

    @Test
    public void hproseHttpClientAndServerExchangeCalls() throws Exception {
        Configuration serverSettings = this.configuration(), clientSettings = this.configuration();
        serverSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        RsfContext server = this.context(serverSettings), client = this.context(clientSettings);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService()).name("custom").aliasName("Hprose", "customEcho").serialize("Hprose").register();
        RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).name("custom").aliasName("Hprose", "customEcho").serialize("Hprose").register();
        assertEquals(service.getBindID(), server.getServiceInfo("Hprose", "customEcho").getBindID());
        assertNull(server.getServiceInfo("Hprose", "echo"));
        Supplier<Echo> provider = EchoService::new;
        server.publisher().rsfService(Echo.class, provider).serialize("Hprose").register();
        RsfBindInfo<Echo> defaults = client.publisher().rsfService(Echo.class).serialize("Hprose").register();
        server.start();
        client.start();
        RsfClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());
        assertEquals("default alias", caller.getRemote(defaults).echo("default alias"));
        Echo remote = caller.getRemote(service);
        assertEquals("HTTP双端", remote.echo("HTTP双端"));
        assertEquals(7, remote.add(3, 4));
        assertNull(remote.echo(null));
        try {
            remote.fail();
            fail("Remote error must not become a successful null response");
        } catch (Exception expected) {
            Throwable cause = expected;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertTrue(cause.toString(), cause instanceof RsfException);
        }
    }

    @Test
    public void httpRpcTimeoutAndCancellationAllowSubsequentCalls() throws Exception {
        Configuration serverSettings = this.configuration();
        Configuration clientSettings = this.configuration();
        serverSettings.setQueueMinPoolSize(2);
        serverSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        RsfContext server = this.context(serverSettings);
        RsfContext client = this.context(clientSettings);
        BlockingQueue<Boolean> entered = new LinkedBlockingQueue<>();
        CountDownLatch release = new CountDownLatch(1);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
            @Override
            public String slow(int millis) throws InterruptedException {
                entered.add(true);
                release.await();
                return "late";
            }
        }).aliasName("Hprose", "deadlineEcho").serialize("Hprose").register();
        RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).aliasName("Hprose", "deadlineEcho").serialize("Hprose").timeout(500).register();
        server.start();
        client.start();
        RsfClient remote = client.getRsfClient(server.bindAddress("hprose"));
        try {
            RsfFuture expired = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
            assertNotNull(entered.poll(3, TimeUnit.SECONDS));
            assertTrue(assertFailure(expired) instanceof RsfTimeoutException);
            assertEquals("after timeout", remote.getRemote(service).echo("after timeout"));
            RsfFuture cancelled = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
            assertNotNull(entered.poll(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel());
            release.countDown();
            assertEquals("after cancel", remote.getRemote(service).echo("after cancel"));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void serverRpcDeadlineFinishesHttpExchangeWhileServiceIsRunning() throws Exception {
        Configuration serverSettings = this.configuration();
        serverSettings.setDefaultTimeout(200);
        Configuration clientSettings = this.configuration();
        serverSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", this.port()).protocol("hprose").contextPath("/hprose");
        RsfContext server = this.context(serverSettings);
        RsfContext client = this.context(clientSettings);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
            @Override
            public String slow(int millis) throws InterruptedException {
                entered.countDown();
                release.await();
                return "late";
            }
        }).aliasName("Hprose", "serverDeadline").serialize("Hprose").timeout(200).register();
        RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).aliasName("Hprose", "serverDeadline").serialize("Hprose").timeout(5000).register();
        server.start();
        client.start();
        RsfClient remote = client.getRsfClient(server.bindAddress("hprose"));
        try {
            RsfFuture call = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                call.getData(3, TimeUnit.SECONDS);
                fail("Server deadline must return an error before the client deadline");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof RsfException);
                assertFalse(expected.getCause() instanceof RsfTimeoutException);
            }
            release.countDown();
            assertEquals("next", remote.getRemote(service).echo("next"));
        } finally {
            release.countDown();
        }
    }

    private String invokeHttp(int port, String path, String value) throws Exception {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write('C');
        HproseWriter writer = new HproseWriter(request);
        writer.writeString("echo_echo");
        writer.writeArray(new Object[] { value });
        request.write('z');
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toByteArray());
            }
            assertEquals(200, connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                assertEquals('R', in.read());
                String result = new HproseReader(in).unserialize(String.class);
                assertEquals('z', in.read());
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

}
