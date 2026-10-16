/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.RouteReceiver;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** HTTP upgrade authorization ordering at the Neta handshake boundary. */
public class HttpUpgradeAuthorizationTest {
    @Test(timeout = 10000)
    public void http1AuthorizationCompletesAfterTheReceivePipelineReleasesItsChannelLock() throws Exception {
        // Hold the simulated Neta receive pipeline after its authorizer returns.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ws"));
        Map<String, String> options = Map.of("upgrade", "websocket");
        RouteSelection expected = RouteTestAccess.selection("jsonws", paths.get("/ws"), options);
        AtomicReference<RouteSelection> selected = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        Thread protocol = new Thread(() -> {
            try {
                authorize(executor, uri -> expected, "/ws", route -> {
                    selected.set(route);
                    completed.countDown();
                }, returned, releaseReceive);
            } catch (Throwable caught) {
                failure.set(caught);
                completed.countDown();
            }
        }, "neta-protocol-test");
        try {
            protocol.start();
            assertTrue(returned.await(3, TimeUnit.SECONDS));

            // The worker may select the route, but it cannot complete the callback inside the receive pipeline.
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            releaseReceive.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            protocol.join(3000);

            // Completion observes the selected route after the protocol thread releases the channel monitor.
            assertNull(failure.get());
            assertSame(expected, selected.get());
        } finally {
            releaseReceive.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void http1AuthorizationWaitsForAnEarlierResponseToFinish() throws Exception {
        // Register an ordinary HTTP request before asking the same connection to upgrade.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ws"));
        Map<String, String> options = Map.of("upgrade", "websocket");
        RouteSelection expected = RouteTestAccess.selection("jsonws", paths.get("/ws"), options);
        PendingAuthorization fixture = new PendingAuthorization(executor, uri -> expected);
        try {
            HttpExchange ordinary = fixture.request().get(3, TimeUnit.SECONDS);
            AtomicReference<RouteSelection> selected = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            fixture.authorize("/ws", route -> {
                selected.set(route);
                completed.countDown();
            });

            // The WebSocket result cannot overtake the response already registered on this HTTP/1 connection.
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            fixture.respond(ordinary).get(3, TimeUnit.SECONDS);
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertSame(expected, selected.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void authorize(Executor executor, Function<String, RouteSelection> router, String uri, //
            Consumer<RouteSelection> callback, CountDownLatch returned, CountDownLatch releaseReceive) //
            throws InterruptedException {
        // 构造可控通道，模拟接收管线持有的物理连接锁。
        Owner owner = new Owner(executor, router);
        NetChannel socket = new OpenSocket();
        Function<HttpRequest, HttpExchange> handoff = request -> null;
        Runnable closeAction = () -> {
        };
        BooleanSupplier available = () -> true;
        Consumer<Throwable> failureAction = failure -> {
        };
        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(//
                owner, socket, true, 1, 0, handoff, closeAction, available, failureAction);
        // 回调必须等待接收管线释放锁后再交付应用。
        synchronized (socket) {
            coordinator.authorize(uri, callback);
            returned.countDown();
            releaseReceive.await();
        }
    }

    private static final class PendingAuthorization {
        private final Http1ServerChannel        channel;
        private final BasicFuture<HttpExchange> request = new BasicFuture<>();

        private PendingAuthorization(Executor executor, Function<String, RouteSelection> router) throws Exception {
            // 先接纳普通 HTTP 请求，保留尚未完成的响应。
            RouteMountIndex mounts = RouteTestAccess.mountIndex(Map.of("contextPath", "/ordinary"));
            Function<HttpExchange, RouteProbeResult> probe = message -> RouteProbeResult.MATCH;
            RouteDefinition<HttpExchange> ordinary = RouteTestAccess.definition(Map.of(), mounts, probe);
            RouteTable<HttpExchange> routes = RouteTestAccess.activate(Map.of("ordinary", ordinary), router);
            this.channel = new Http1ServerChannel(Owner.config(), null, new OpenSocket(), executor, routes);
            this.channel.attach(channel -> new RouteReceiver<>() {
                @Override
                public void connected() {
                }

                @Override
                public void receive(HttpExchange exchange) {
                    PendingAuthorization.this.request.completed(exchange);
                }

                @Override
                public void receive(String route, HttpExchange exchange) {
                    this.receive(exchange);
                }

                @Override
                public void closed(Throwable cause) {
                    PendingAuthorization.this.request.failed(cause);
                }
            });

            // 注入已匹配路由的请求，供升级授权测试观察顺序。
            HttpRequest inbound = HttpRequest.newBuffered("GET", "/ordinary", Map.of(), new byte[0], 1, Map.of());
            RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ordinary"));
            RouteSelection route = RouteTestAccess.selection("ordinary", paths.get("/ordinary"), Map.of());
            HttpMessageUtils.route(inbound, route);
            this.channel.received(inbound, true);
        }

        private Future<HttpExchange> request() {
            return this.request;
        }

        private void authorize(String uri, Consumer<RouteSelection> callback) {
            this.channel.authorizeUpgrade(uri, callback);
        }

        private Future<Void> respond(HttpExchange exchange) {
            exchange.respond(HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of()));
            return this.channel.write(exchange);
        }
    }

    private static final class Owner extends HttpChannel {
        private Owner(Executor executor, Function<String, RouteSelection> router) {
            super(config(), null, executor, RouteTestAccess.activate(Map.of(), router), null);
        }

        private static TransportConfig config() {
            InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
            return new TransportConfig("unit", address, false, Map.of(), Map.of());
        }

        @Override
        public InterAddress getLocal() {
            return this.config.address();
        }

        @Override
        public InterAddress getRemote() {
            return this.config.address();
        }

        @Override
        public boolean isOpen() {
            return !this.closeFuture().isDone();
        }

        @Override
        public Future<Void> write(HttpExchange message) {
            throw new UnsupportedOperationException("Unit owner only authorizes upgrades");
        }

        @Override
        public Future<Void> close() {
            this.closeFuture().completed(null);
            return this.closeFuture();
        }
    }

    private static final class OpenSocket extends NetChannel {
        private OpenSocket() {
            super(1, null, null, stack -> {
            }, null, null);
        }

        @Override
        public SoConfig getConfig() {
            return new TcpSoConfig();
        }

        @Override
        public InetSocketAddress getLocalAddr() {
            return new InetSocketAddress("127.0.0.1", 12000);
        }

        @Override
        public InetSocketAddress getRemoteAddr() {
            return new InetSocketAddress("127.0.0.1", 12001);
        }

        @Override
        public boolean isClose() {
            return false;
        }

        @Override
        protected void pauseTransportRead() {
        }

        @Override
        protected void resumeTransportRead() {
        }

        @Override
        public Future<?> sendData(Object writeData) {
            return new BasicFuture<>(this);
        }

        @Override
        public Future<?> sendData(Object[] writeData) {
            return new BasicFuture<>(this);
        }
    }
}
