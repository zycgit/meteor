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

/** HTTP/1 升级排序的包内测试访问入口。 */
public final class Http1UpgradeTestAccess {
    private Http1UpgradeTestAccess() {
    }

    public static void authorize(Executor executor, Function<String, RouteSelection> router, String uri, Consumer<RouteSelection> callback, CountDownLatch returned, CountDownLatch releaseReceive) throws InterruptedException {
        Owner owner = new Owner(executor, router);
        NetChannel socket = new OpenSocket();
        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(owner, socket, true, 1, 0, request -> null, () -> {
        }, () -> true, failure -> {
        });
        synchronized (socket) {
            coordinator.authorize(uri, callback);
            returned.countDown();
            releaseReceive.await();
        }
    }

    public static PendingAuthorization pending(Executor executor, Function<String, RouteSelection> router) throws Exception {
        return new PendingAuthorization(executor, router);
    }

    public static final class PendingAuthorization {
        private final Http1ServerChannel        channel;
        private final BasicFuture<HttpExchange> request = new BasicFuture<>();

        private PendingAuthorization(Executor executor, Function<String, RouteSelection> router) throws Exception {
            RouteMountIndex mounts = RouteTestAccess.mountIndex(Map.of("contextPath", "/ordinary"));
            RouteDefinition<HttpExchange> ordinary = RouteTestAccess.definition(Map.of(), mounts, message -> RouteProbeResult.MATCH);
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

            HttpRequest inbound = HttpRequest.newBuffered("GET", "/ordinary", Map.of(), new byte[0], 1, Map.of());
            RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ordinary"));
            RouteSelection route = RouteTestAccess.selection("ordinary", paths.get("/ordinary"), Map.of());
            HttpMessageUtils.route(inbound, route);
            this.channel.received(inbound, true);
        }

        public Future<HttpExchange> request() {
            return this.request;
        }

        public void authorize(String uri, Consumer<RouteSelection> callback) {
            this.channel.authorizeUpgrade(uri, callback);
        }

        public Future<Void> respond(HttpExchange exchange) {
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
