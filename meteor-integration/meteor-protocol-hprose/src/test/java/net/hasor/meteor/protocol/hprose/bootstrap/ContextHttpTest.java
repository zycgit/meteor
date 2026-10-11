/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextHttpTest extends BootstrapTestSupport {

    @Test
    public void hproseHttpRequestReachesRegisteredServiceWithoutHasor() throws Exception {
        // 使用原生 HTTP 客户端发送 Hprose 报文，验证协议入口。
        MetConfiguration config = this.configuration();
        config.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        try (MetContext server = config.buildContext()) {
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            server.start();
            int httpPort = server.bindAddress("hprose").getPort();
            assertEquals("HTTP中文", this.invokeHttp(httpPort, "/hprose", "HTTP中文"));
        }
    }

    @Test
    public void mountedProtocolsShareConfiguredPortAndAdvertiseTheirOwnSchemes() throws Exception {
        // 两个协议挂载共享 HTTP 端口，按路径分流。
        MetConfiguration config = this.configuration();
        int sharedPort = this.port();
        MetConfiguration.ConnectorSettings http = config.connector("http").bind("127.0.0.1", sharedPort);
        http.protocol("hprosea").contextPath("/a");
        http.protocol("hproseb").contextPath("/b");
        try (MetContext server = config.buildContext()) {
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
    }

    @Test
    public void hproseHttpClientAndServerExchangeCalls() throws Exception {
        // 通过框架客户端验证默认别名、自定义别名和业务异常。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            ConfigurationBuilder<Echo> published = server.publisher().rsfService(Echo.class, new EchoService());
            published.name("custom");
            published.aliasName("Hprose", "customEcho");
            published.serialize("Hprose");
            published.register();
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name("custom");
            subscribed.aliasName("Hprose", "customEcho");
            subscribed.serialize("Hprose");
            MetBindInfo<Echo> service = subscribed.register();
            assertEquals(service.getBindID(), server.getServiceInfo("Hprose", "customEcho").getBindID());
            assertNull(server.getServiceInfo("Hprose", "echo"));
            Supplier<Echo> provider = EchoService::new;
            server.publisher().rsfService(Echo.class, provider).serialize("Hprose").register();
            MetBindInfo<Echo> defaults = client.publisher().rsfService(Echo.class).serialize("Hprose").register();

            server.start();
            client.start();

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            // 默认别名与自定义别名分别定位到预期服务。
            assertEquals("default alias", caller.getRemote(defaults).echo("default alias"));
            Echo remote = caller.getRemote(service);
            assertEquals("HTTP双端", remote.echo("HTTP双端"));
            assertEquals(7, remote.add(3, 4));
            assertNull(remote.echo(null));

            // 业务失败由远端错误响应返回，不能成为成功的 null。
            Exception failure = assertThrows(Exception.class, remote::fail);
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertTrue(cause.toString(), cause instanceof MetException);
        }
    }

    @Test
    public void httpRpcTimeoutAndCancellationAllowSubsequentCalls() throws Exception {
        // 超时及取消只结束对应调用，后续请求仍可使用 HTTP 通道。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings.setQueueMinPoolSize(2);
        serverSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            BlockingQueue<Boolean> entered = new LinkedBlockingQueue<>();
            CountDownLatch release = new CountDownLatch(1);
            server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
                @Override
                public String slow(int millis) throws InterruptedException {
                    entered.add(true);
                    assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                    return "late";
                }
            }).aliasName("Hprose", "deadlineEcho").serialize("Hprose").register();
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.aliasName("Hprose", "deadlineEcho");
            subscribed.serialize("Hprose");
            subscribed.timeout(500);
            MetBindInfo<Echo> service = subscribed.register();

            server.start();
            client.start();

            MetClient remote = client.getRsfClient(server.bindAddress("hprose"));
            Class<?>[] slowTypes = { int.class };
            Object[] slowArguments = { 1 };
            try {
                MetFuture expired = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
                assertNotNull(entered.poll(3, TimeUnit.SECONDS));
                assertTrue(assertFailure(expired) instanceof MetTimeoutException);
                assertEquals("after timeout", remote.getRemote(service).echo("after timeout"));

                // 取消另一条正在执行的调用，释放服务后通道仍可复用。
                MetFuture cancelled = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
                assertNotNull(entered.poll(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                release.countDown();
                assertEquals("after cancel", remote.getRemote(service).echo("after cancel"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void serverRpcDeadlineFinishesHttpExchangeWhileServiceIsRunning() throws Exception {
        // 服务端超时先于客户端发生，必须及时结束 HTTP 交换。
        MetConfiguration serverSettings = this.configuration();
        serverSettings.setDefaultTimeout(200);
        MetConfiguration clientSettings = this.configuration();
        serverSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        clientSettings.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
                @Override
                public String slow(int millis) throws InterruptedException {
                    entered.countDown();
                    assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                    return "late";
                }
            }).aliasName("Hprose", "serverDeadline").serialize("Hprose").timeout(200).register();
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.aliasName("Hprose", "serverDeadline");
            subscribed.serialize("Hprose");
            subscribed.timeout(5000);
            MetBindInfo<Echo> service = subscribed.register();

            server.start();
            client.start();

            MetClient remote = client.getRsfClient(server.bindAddress("hprose"));
            try {
                MetFuture call = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var failure = assertThrows(ExecutionException.class, () -> call.getData(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                assertFalse(failure.getCause() instanceof MetTimeoutException);
                release.countDown();
                assertEquals("next", remote.getRemote(service).echo("next"));
            } finally {
                release.countDown();
            }
        }
    }

    private String invokeHttp(int port, String path, String value) throws Exception {
        // 构造 Hprose 调用帧，方法名与参数使用独立引用表。
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write('C');
        HproseWriter writer = new HproseWriter(request);
        writer.writeString("echo_echo");
        writer.reset();
        writer.writeArray(new Object[] { value });
        request.write('z');

        // 使用有界 HTTP 连接发送原始报文，不经过框架客户端编码。
        URL endpoint = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toByteArray());
            }

            // 独立解码响应，确认返回值与终止标记，而不仅是 HTTP 成功状态。
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
