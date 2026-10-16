/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;
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
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.example.protocol.hprose.Signal;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextRpcTest extends BootstrapTestSupport {

    @Test
    public void concurrentAsyncCallsKeepTheirOwnResults() throws Exception {
        // 准备两端容器，在启动后注册回显服务。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 服务端发布接口，调用方订阅相同接口。
            server.start();
            client.start();

            server.publisher().rsfService(Echo.class).to(EchoService.class).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            // 连续提交调用，不等待上一条返回，形成并发请求。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            List<MetFuture> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Class<?>[] types = { String.class };
                Object[] arguments = { "call-" + i };
                MetFuture call = remote.asyncInvoke(service, "echo", types, arguments);
                calls.add(call);
            }

            // 每个 Future 只能收到对应请求的结果，不能被其他响应覆盖。
            for (int i = 0; i < calls.size(); i++) {
                assertEquals("call-" + i, calls.get(i).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void callbackInvokeCompletesACobbleFuture() throws Exception {
        // 启动两端容器，并通过公开 API 发布和订阅回显服务。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            server.start();
            client.start();

            server.publisher().rsfService(Echo.class).to(EchoService.class).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            // 将成功或失败回调转交 Cobble Future，便于有界等待结果。
            BasicFuture<Object> callback = new BasicFuture<>();
            FutureCallback<Object> listener = new FutureCallback<>() {
                public void completed(Object result) {
                    callback.completed(result);
                }

                public void failed(Throwable failure) {
                    callback.failed(failure);
                }
            };

            // 发起一次回调调用，验证回调完成了对应的 Future。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "callback" };
            remote.callbackInvoke(service, "echo", types, arguments, listener);
            assertEquals("callback", callback.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void remoteBusinessFailurePreservesExceptionType() throws Exception {
        // 为会抛出业务异常的回显服务准备两端容器。
        MetConfiguration serverConfig = this.configuration();
        serverConfig.setQueueMinPoolSize(2);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            // 启动后通过 Provider 发布服务，并配置调用期限。
            server.start();
            client.start();

            server.publisher().rsfService(Echo.class).toProvider(EchoService::new).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(250).register();

            // 调用失败方法，远端业务异常应以 MetException 反馈给调用方。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            MetFuture failed = remote.asyncInvoke(service, "fail", new Class<?>[0], new Object[0]);
            assertTrue(assertFailure(failed) instanceof MetException);
        }
    }

    @Test
    public void remoteDeadlineReportsTimeout() throws Exception {
        // 为耗时方法准备服务端，调用方使用更短的请求期限。
        MetConfiguration serverConfig = this.configuration();
        serverConfig.setQueueMinPoolSize(2);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            // 启动后发布服务，调用期限设为 250 毫秒。
            server.start();
            client.start();

            server.publisher().rsfService(Echo.class).toProvider(EchoService::new).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(250).register();

            // 服务执行 1 秒，调用方应先收到请求超时。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            Class<?>[] slowTypes = { int.class };
            Object[] slowArguments = { 1000 };
            MetFuture expired = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
            assertTrue(assertFailure(expired) instanceof MetTimeoutException);
        }
    }

    @Test
    public void offlineStopsListeningAndClosesExistingConnection() throws Exception {
        // 准备已发布回显服务的服务端，并建立一条真实远程连接。
        MetConfiguration serverConfig = this.configuration();
        serverConfig.setQueueMinPoolSize(2);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            // 启动后发布服务，先确认既有连接可以完成调用。
            server.start();
            client.start();

            server.publisher().rsfService(Echo.class).toProvider(EchoService::new).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(250).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            assertEquals("online", remote.getRemote(service).echo("online"));

            // 离线关闭监听和既有入站连接，后续调用以连接失败结束。
            server.offline();
            assertNull(server.getListenAddress("rsf"));
            Class<?>[] types = { String.class };
            MetFuture call = remote.asyncInvoke(service, "echo", types, new Object[] { "offline" });
            assertNotNull(assertFailure(call));
        }
    }

    @Test
    public void expiredCallIsNotSentWhenHandshakeCompletesLate() throws Exception {
        // 延后握手完成时间，已经超时的请求不能继续写入网络。
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            MetConfiguration clientConfiguration = this.configuration();
            try (MetContext client = clientConfiguration.buildContext()) {
                // 客户端启动后订阅服务，将请求期限缩短到握手完成之前。
                client.start();
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(150).register();

                // 连接到受测试控制的对端，先发起请求但暂不回复握手。
                InterAddress address = new InterAddress("rsf", "127.0.0.1", peer.getLocalPort(), "default");
                MetClient remote = client.getRsfClient(address);
                Class<?>[] types = { String.class };
                MetFuture call = remote.asyncInvoke(service, "echo", types, new Object[] { "expired" });
                peer.setSoTimeout(2000);
                try (Socket socket = peer.accept()) {
                    // 读取完整握手帧，并等待尚未发送的调用超时。
                    socket.setSoTimeout(2000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    byte[] header = new byte[13];
                    input.readFully(header);
                    int highLength = (header[10] & 255) << 16;
                    int middleLength = (header[11] & 255) << 8;
                    int lowLength = header[12] & 255;
                    int length = highLength | middleLength | lowLength;
                    byte[] body = new byte[length];
                    input.readFully(body);
                    assertTrue(assertFailure(call) instanceof MetTimeoutException);

                    // RPC 超时后才完成握手，随后观察套接字不得收到过期请求。
                    socket.getOutputStream().write(header);
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    socket.setSoTimeout(300);
                    String failureMessage = "Expired request reached the socket";
                    assertThrows(failureMessage, SocketTimeoutException.class, input::read);
                }
            }
        }
    }

    @Test
    public void messageModeCompletesOnAcceptWhileHandlerIsStillRunning() throws Exception {
        // 消息模式在接收确认后完成，不等待服务执行结束。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，在服务执行中设置可观察的进入和退出信号。
            server.start();
            client.start();

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            Signal handler = value -> {
                entered.countDown();
                assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                finished.countDown();
            };
            server.publisher().rsfService(Signal.class).toInstance(handler).asMessage().register();
            MetBindInfo<Signal> service = client.publisher().rsfService(Signal.class).asMessage().register();

            try {
                // 接收确认使发送 Future 完成，此时处理方法仍在等待放行。
                MetClient caller = client.getRsfClient(server.getListenAddress("rsf"));
                Class<?>[] types = { String.class };
                MetFuture sent = caller.asyncInvoke(service, "accept", types, new Object[] { "message" });
                sent.getData(5, TimeUnit.SECONDS);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, finished.getCount());
            } finally {
                // 无论断言是否成功，都放行处理方法，避免阻塞关闭流程。
                release.countDown();
            }
            // 放行后服务端应正常执行结束。
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void rsfWithJavaPreservesUnicode() throws Exception {
        // 准备 RSF 两端，通过 Java 序列化验证中文和补充平面字符。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Java。
            String format = "Java";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证中文和补充平面字符往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJavaPreservesEmptyString() throws Exception {
        // 准备 RSF 两端，通过 Java 序列化验证空字符串。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Java。
            String format = "Java";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证空字符串往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("", remote.echo(""));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJavaPreservesNull() throws Exception {
        // 准备 RSF 两端，通过 Java 序列化验证 null。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Java。
            String format = "Java";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证 null 往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertNull(remote.echo(null));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJavaPreservesPrimitiveParameters() throws Exception {
        // 准备 RSF 两端，通过 Java 序列化验证基本类型参数。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Java。
            String format = "Java";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证基本类型参数往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals(5, remote.add(2, 3));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJavaPreservesArray() throws Exception {
        // 准备 RSF 两端，通过 Java 序列化验证数组内容。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Java。
            String format = "Java";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证数组内容往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertArrayEquals(new int[] { 1, 2, 3 }, remote.array(new int[] { 1, 2, 3 }));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJsonPreservesUnicode() throws Exception {
        // 准备 RSF 两端，通过 Json 序列化验证中文和补充平面字符。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Json。
            String format = "Json";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证中文和补充平面字符往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJsonPreservesEmptyString() throws Exception {
        // 准备 RSF 两端，通过 Json 序列化验证空字符串。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Json。
            String format = "Json";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证空字符串往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("", remote.echo(""));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJsonPreservesNull() throws Exception {
        // 准备 RSF 两端，通过 Json 序列化验证 null。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Json。
            String format = "Json";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证 null 往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertNull(remote.echo(null));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJsonPreservesPrimitiveParameters() throws Exception {
        // 准备 RSF 两端，通过 Json 序列化验证基本类型参数。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Json。
            String format = "Json";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证基本类型参数往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals(5, remote.add(2, 3));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithJsonPreservesArray() throws Exception {
        // 准备 RSF 两端，通过 Json 序列化验证数组内容。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Json。
            String format = "Json";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证数组内容往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertArrayEquals(new int[] { 1, 2, 3 }, remote.array(new int[] { 1, 2, 3 }));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithHprosePreservesUnicode() throws Exception {
        // 准备 RSF 两端，通过 Hprose 序列化验证中文和补充平面字符。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hprose。
            String format = "Hprose";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证中文和补充平面字符往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithHprosePreservesEmptyString() throws Exception {
        // 准备 RSF 两端，通过 Hprose 序列化验证空字符串。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hprose。
            String format = "Hprose";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证空字符串往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("", remote.echo(""));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithHprosePreservesNull() throws Exception {
        // 准备 RSF 两端，通过 Hprose 序列化验证 null。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hprose。
            String format = "Hprose";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证 null 往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertNull(remote.echo(null));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithHprosePreservesPrimitiveParameters() throws Exception {
        // 准备 RSF 两端，通过 Hprose 序列化验证基本类型参数。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hprose。
            String format = "Hprose";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证基本类型参数往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals(5, remote.add(2, 3));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void rsfWithHprosePreservesArray() throws Exception {
        // 准备 RSF 两端，通过 Hprose 序列化验证数组内容。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 启动两端容器，服务在启动后注册。
            server.start();
            client.start();

            // 通过过滤器记录实际收到的 RPC，确认调用经过网络。
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            // 发布和订阅使用不同大小写的序列化名称，均应选择 Hprose。
            String format = "Hprose";
            var published = server.publisher().rsfService(Echo.class, EchoService.class);
            published.name(format);
            published.serialize(format.toLowerCase(Locale.ROOT));
            published.register();
            var subscribed = client.publisher().rsfService(Echo.class);
            subscribed.name(format);
            subscribed.serialize(format.toUpperCase(Locale.ROOT));
            MetBindInfo<Echo> service = subscribed.register();

            // 验证数组内容往返保留，并且服务只收到一次调用。
            Echo remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertArrayEquals(new int[] { 1, 2, 3 }, remote.array(new int[] { 1, 2, 3 }));
            assertEquals(1, received.get());
            assertEquals(Collections.singleton("rsf"), server.runProtocols());
        }
    }

    @Test
    public void fixedTargetRetainsLocalPreferenceWhenBothContextsProvideTheService() throws Exception {
        // 显式指定目标地址不改变服务默认的本地优先策略。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 两端分别发布同名服务，以返回值区分本地和远端实现。
            server.start();
            client.start();

            EchoService remoteImplementation = new EchoService() {
                @Override
                public String echo(String value) {
                    return "remote";
                }
            };
            server.publisher().rsfService(Echo.class, remoteImplementation).register();
            EchoService localImplementation = new EchoService() {
                @Override
                public String echo(String value) {
                    return "local";
                }
            };
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class, localImplementation).register();

            // 普通调用和固定地址调用都优先本地实现。
            assertEquals("local", client.getRsfClient().getRemote(service).echo("value"));
            Echo fixedTarget = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("local", fixedTarget.echo("value"));
        }
    }

    @Test
    public void distributedServiceUsesTheAddressBookInsteadOfTheLocalProvider() throws Exception {
        // 服务显式声明分布式模式后，默认客户端必须通过地址本访问目标实例。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 两端分别发布同名服务，以返回值区分本地和远端实现。
            server.start();
            client.start();

            EchoService remoteImplementation = new EchoService() {
                @Override
                public String echo(String value) {
                    return "remote";
                }
            };
            server.publisher().rsfService(Echo.class, remoteImplementation).register();
            EchoService localImplementation = new EchoService() {
                @Override
                public String echo(String value) {
                    return "local";
                }
            };
            MetBindInfo<Echo> service = client.publisher() //
                    .rsfService(Echo.class, localImplementation) //
                    .bindAddress(server.getListenAddress("rsf")) //
                    .asDistributedCall() //
                    .register();

            // 本地提供者仍然存在，但分布式模式使请求进入地址本调用链。
            assertTrue(service.isDistributedCall());
            Echo distributed = client.getRsfClient().getRemote(service);
            assertEquals("remote", distributed.echo("value"));
        }
    }
}
