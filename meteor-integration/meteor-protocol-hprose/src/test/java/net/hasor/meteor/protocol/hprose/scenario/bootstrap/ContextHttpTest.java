/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap;
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
import net.hasor.meteor.protocol.hprose.scenario.example.Echo;
import net.hasor.meteor.protocol.hprose.scenario.example.EchoService;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextHttpTest extends BootstrapTestSupport {

    @Test
    public void hproseHttpRequestReachesRegisteredServiceWithoutHasor() throws Exception {
        // 使用原生 HTTP 客户端发送 Hprose 报文，验证协议入口。
        MetConfiguration config = this.configuration();
        config.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/hprose");
        try (MetContext server = config.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();

            // 将 Echo 实现发布到已启动的协议入口。
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            // 原生 HTTP 调用经过 Hprose 解码后，返回原始中文参数。
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
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();

            // 将 Echo 实现发布到已启动的协议入口。
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            // 两个挂载分别公开自己的 scheme，并在同一端口响应对应路径。
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
    public void defaultAliasSelectsThePublishedSupplier() throws Exception {
        // 配置两端 Hprose 入口，验证默认别名可独立选择 Supplier 发布的服务。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 先以自定义名称发布和订阅，确认别名映射指向同一服务。
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
            MetBindInfo<?> namedService = server.getServiceInfo("Hprose", "customEcho");
            assertEquals(service.getBindID(), namedService.getBindID());
            assertNull(server.getServiceInfo("Hprose", "echo"));

            // 再通过 Supplier 发布默认名称，保持它与自定义别名的独立映射。
            Supplier<Echo> provider = EchoService::new;
            server.publisher().rsfService(Echo.class, provider).serialize("Hprose").register();
            MetBindInfo<Echo> defaults = client.publisher().rsfService(Echo.class).serialize("Hprose").register();

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            // 使用默认订阅调用，确认请求选择默认名称的实现。
            assertEquals("default alias", caller.getRemote(defaults).echo("default alias"));
        }
    }

    @Test
    public void customAliasSelectsTheNamedService() throws Exception {
        // 配置 Hprose 挂载，使用独立应用经网络调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 先以自定义名称发布和订阅，确认别名映射指向同一服务。
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
            MetBindInfo<?> namedService = server.getServiceInfo("Hprose", "customEcho");
            assertEquals(service.getBindID(), namedService.getBindID());
            assertNull(server.getServiceInfo("Hprose", "echo"));

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            // 按订阅的自定义别名发起真实 HTTP 调用。
            assertEquals("HTTP双端", caller.getRemote(service).echo("HTTP双端"));
        }
    }

    @Test
    public void httpClientPreservesPrimitiveParameters() throws Exception {
        // 配置 Hprose 挂载，使用独立应用经网络调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 发布 Echo 实现，并订阅相同的 Hprose 编码服务。
            server.publisher() //
                    .rsfService(Echo.class, new EchoService()) //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).serialize("Hprose").register();

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            // 基本类型参数经过 HTTP 编解码后仍参与正常加法运算。
            assertEquals(7, caller.getRemote(service).add(3, 4));
        }
    }

    @Test
    public void httpClientPreservesNullResult() throws Exception {
        // 配置 Hprose 挂载，使用独立应用经网络调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 发布 Echo 实现，并订阅相同的 Hprose 编码服务。
            server.publisher() //
                    .rsfService(Echo.class, new EchoService()) //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).serialize("Hprose").register();

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            // null 作为正常业务结果返回，不被转换为错误或空字符串。
            assertNull(caller.getRemote(service).echo(null));
        }
    }

    @Test
    public void httpBusinessFailureIsAnErrorResponse() throws Exception {
        // 配置 Hprose 挂载，使用独立应用经网络调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 发布 Echo 实现，并订阅相同的 Hprose 编码服务。
            server.publisher() //
                    .rsfService(Echo.class, new EchoService()) //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).serialize("Hprose").register();

            MetClient caller = client.getRsfClient(server.bindAddress("hprose").toHostSchema());

            Echo remote = caller.getRemote(service);

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
    public void httpRpcTimeoutAllowsSubsequentCalls() throws Exception {
        // 配置客户端期限，并保留额外执行线程验证超时后的调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings.setQueueMinPoolSize(2);
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 用信号控制慢方法的进入和释放，避免靠休眠推测调用状态。
            BlockingQueue<Boolean> entered = new LinkedBlockingQueue<>();
            CountDownLatch release = new CountDownLatch(1);
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService() {
                        @Override
                        public String slow(int millis) throws InterruptedException {
                            entered.add(true);
                            boolean released = release.await(10, TimeUnit.SECONDS);
                            assertTrue("Handler was not released", released);
                            return "late";
                        }
                    }) //
                    .aliasName("Hprose", "deadlineEcho") //
                    .serialize("Hprose") //
                    .register();

            // 客户端订阅慢方法，并为调用设置有界期限。
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.aliasName("Hprose", "deadlineEcho");
            subscribed.serialize("Hprose");
            subscribed.timeout(500);
            MetBindInfo<Echo> service = subscribed.register();

            MetClient remote = client.getRsfClient(server.bindAddress("hprose"));
            Class<?>[] slowTypes = { int.class };
            Object[] slowArguments = { 1 };
            try {
                // 确认业务已经进入，再等待客户端期限结束此次调用。
                MetFuture expired = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
                assertNotNull(entered.poll(3, TimeUnit.SECONDS));
                assertTrue(assertFailure(expired) instanceof MetTimeoutException);

                // 超时不破坏连接，普通请求仍能通过同一客户端完成。
                assertEquals("after timeout", remote.getRemote(service).echo("after timeout"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void httpRpcCancellationAllowsSubsequentCalls() throws Exception {
        // 配置可取消的调用，并保留额外执行线程验证取消后的调用。
        MetConfiguration serverSettings = this.configuration();
        MetConfiguration clientSettings = this.configuration();
        serverSettings.setQueueMinPoolSize(2);
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 用信号控制慢方法的进入和释放，避免靠休眠推测调用状态。
            BlockingQueue<Boolean> entered = new LinkedBlockingQueue<>();
            CountDownLatch release = new CountDownLatch(1);
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService() {
                        @Override
                        public String slow(int millis) throws InterruptedException {
                            entered.add(true);
                            boolean released = release.await(10, TimeUnit.SECONDS);
                            assertTrue("Handler was not released", released);
                            return "late";
                        }
                    }) //
                    .aliasName("Hprose", "deadlineEcho") //
                    .serialize("Hprose") //
                    .register();

            // 客户端订阅慢方法，并为调用设置有界期限。
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.aliasName("Hprose", "deadlineEcho");
            subscribed.serialize("Hprose");
            subscribed.timeout(500);
            MetBindInfo<Echo> service = subscribed.register();

            MetClient remote = client.getRsfClient(server.bindAddress("hprose"));
            Class<?>[] slowTypes = { int.class };
            Object[] slowArguments = { 1 };
            try {
                // 等待慢方法进入后取消此次调用，不影响同一通道的后续请求。
                MetFuture cancelled = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
                assertNotNull(entered.poll(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());

                // 释放业务方法后，再次发起普通请求验证通道可复用。
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
        serverSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        clientSettings //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/hprose");
        try (MetContext server = serverSettings.buildContext(); //
             MetContext client = clientSettings.buildContext()) {
            // 先启动 HTTP 应用，再发布本场景使用的服务。
            server.start();
            client.start();

            // 服务进入后保持阻塞，让服务端期限先于业务方法完成。
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService() {
                        @Override
                        public String slow(int millis) throws InterruptedException {
                            entered.countDown();
                            boolean released = release.await(10, TimeUnit.SECONDS);
                            assertTrue("Handler was not released", released);
                            return "late";
                        }
                    }) //
                    .aliasName("Hprose", "serverDeadline") //
                    .serialize("Hprose") //
                    .timeout(200) //
                    .register();

            // 客户端期限更长，确保观察到的是服务端返回的超时结果。
            ConfigurationBuilder<Echo> subscribed = client.publisher().rsfService(Echo.class);
            subscribed.aliasName("Hprose", "serverDeadline");
            subscribed.serialize("Hprose");
            subscribed.timeout(5000);
            MetBindInfo<Echo> service = subscribed.register();

            MetClient remote = client.getRsfClient(server.bindAddress("hprose"));
            try {
                // 发起慢调用并等待服务进入，保持方法阻塞以观察服务端期限。
                Class<?>[] parameterTypes = { int.class };
                Object[] arguments = { 1 };
                MetFuture call = remote.asyncInvoke(service, "slow", parameterTypes, arguments);
                assertTrue(entered.await(3, TimeUnit.SECONDS));

                // 客户端收到远端错误，而不是自身等待期限触发的异常。
                var failure = assertThrows(ExecutionException.class, () -> call.getData(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                assertFalse(failure.getCause() instanceof MetTimeoutException);

                // 业务释放后仍能继续处理新的 HTTP 请求。
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
