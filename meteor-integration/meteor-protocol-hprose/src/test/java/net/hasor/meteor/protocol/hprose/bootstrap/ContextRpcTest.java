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
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextRpcTest extends BootstrapTestSupport {

    @Test
    public void concurrentAsyncCallsAndCallbacksUseCobbleFutures() throws Exception {
        // 每轮使用独立上下文，检查并发请求与回调的结果归属。
        for (int round = 0; round < 5; round++) {
            MetConfiguration serverConfiguration = this.configuration();
            MetConfiguration clientConfiguration = this.configuration();
            try (MetContext server = serverConfiguration.buildContext(); //
                 MetContext client = clientConfiguration.buildContext()) {
                server.publisher().rsfService(Echo.class).to(EchoService.class).register();
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

                server.start();
                client.start();

                MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
                List<MetFuture> calls = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    Object[] arguments = { "call-" + i };
                    MetFuture call = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, arguments);
                    calls.add(call);
                }
                for (int i = 0; i < calls.size(); i++) {
                    assertEquals("call-" + i, calls.get(i).getData(5, TimeUnit.SECONDS));
                }

                // 同步取值之外，再验证 Future 回调收到相同结果。
                BasicFuture<Object> callback = new BasicFuture<>();
                FutureCallback<Object> listener = new FutureCallback<>() {
                    public void completed(Object result) {
                        callback.completed(result);
                    }

                    public void failed(Throwable failure) {
                        callback.failed(failure);
                    }
                };
                Class<?>[] types = { String.class };
                Object[] arguments = { "callback" };
                remote.callbackInvoke(service, "echo", types, arguments, listener);
                assertEquals("callback", callback.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void remoteFailureTimeoutAndOfflineStatusRemainObservable() throws Exception {
        // 业务错误、请求超时和目标离线都必须反馈给调用方。
        MetConfiguration serverConfig = this.configuration();
        serverConfig.setQueueMinPoolSize(2);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            server.publisher().rsfService(Echo.class).toProvider(EchoService::new).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(250).register();

            server.start();
            client.start();

            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));

            // 业务异常和超时保留各自类型，不能都退化为超时。
            MetFuture failed = remote.asyncInvoke(service, "fail", new Class<?>[0], new Object[0]);
            assertTrue(assertFailure(failed) instanceof MetException);
            Class<?>[] slowTypes = { int.class };
            Object[] slowArguments = { 1000 };
            MetFuture expired = remote.asyncInvoke(service, "slow", slowTypes, slowArguments);
            assertTrue(assertFailure(expired) instanceof MetTimeoutException);

            // 服务离线属于准入拒绝，仍能收到明确响应。
            server.offline();
            Class<?>[] types = { String.class };
            MetFuture denied = remote.asyncInvoke(service, "echo", types, new Object[] { "offline" });
            Throwable offline = assertFailure(denied);
            assertEquals(ProtocolStatus.Forbidden, ((MetException) offline).getStatus());
            server.online();
        }
    }

    @Test
    public void expiredCallIsNotSentWhenHandshakeCompletesLate() throws Exception {
        // 延后握手完成时间，已经超时的请求不能继续写入网络。
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            MetConfiguration clientConfiguration = this.configuration();
            try (MetContext client = clientConfiguration.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(150).register();

                client.start();

                InterAddress address = new InterAddress("rsf", "127.0.0.1", peer.getLocalPort(), "default");
                MetClient remote = client.getRsfClient(address);
                Class<?>[] types = { String.class };
                MetFuture call = remote.asyncInvoke(service, "echo", types, new Object[] { "expired" });
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

                    // RPC 超时后才完成握手，随后观察套接字不得收到过期请求。
                    socket.getOutputStream().write(header);
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    socket.setSoTimeout(300);
                    assertThrows("Expired request reached the socket", SocketTimeoutException.class, input::read);
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
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            server.publisher().rsfService(Signal.class).toInstance(value -> {
                entered.countDown();
                assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                finished.countDown();
            }).asMessage().register();
            MetBindInfo<Signal> service = client.publisher().rsfService(Signal.class).asMessage().register();

            server.start();
            client.start();
            try {
                MetClient caller = client.getRsfClient(server.bindAddress("rsf"));
                Class<?>[] types = { String.class };
                MetFuture sent = caller.asyncInvoke(service, "accept", types, new Object[] { "message" });
                sent.getData(5, TimeUnit.SECONDS);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, finished.getCount());
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void realRsfCallsUseAllSerializersAndRegisteredFilters() throws Exception {
        // 用真实 RSF 网络分别验证内置序列化器和 Hprose 插件。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("count", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });

            server.start();
            client.start();
            for (String format : new String[] { "Java", "Json", "Hessian", "Hprose" }) {
                var published = server.publisher().rsfService(Echo.class, EchoService.class);
                published.name(format);
                published.serialize(format.toLowerCase(Locale.ROOT));
                published.register();
                var subscribed = client.publisher().rsfService(Echo.class);
                subscribed.name(format);
                subscribed.serialize(format.toUpperCase(Locale.ROOT));
                MetBindInfo<Echo> service = subscribed.register();

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
    }

    @Test
    public void fixedTargetBypassesLocalServiceEvenWhenBothContextsProvideIt() throws Exception {
        // 显式指定目标地址必须访问远端，即使调用方也发布同名服务。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
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
}
