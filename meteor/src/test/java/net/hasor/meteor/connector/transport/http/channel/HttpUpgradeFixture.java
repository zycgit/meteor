/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTestAccess;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;

/** 为升级通道与协议写入测试创建可控的物理连接和所属通道。 */
public final class HttpUpgradeFixture {
    private HttpUpgradeFixture() {
    }

    public static <M> NetworkChannel<M> attach(HttpExchange exchange, Class<M> type, Executor executor, //
            Function<M, Future<?>> writer, Runnable closeAction) {
        Owner owner = new Owner(executor);
        NetChannel socket = new OpenSocket();
        HttpUpgradeChannel<M> channel = new HttpUpgradeChannel<>(owner, socket, type, writer, closeAction);
        exchange.attachUpgrade(channel);
        return channel;
    }

    private static final class Owner extends HttpChannel {
        private Owner(Executor executor) {
            super(config(), null, executor, RouteTestAccess.activate(Map.of(), uri -> null), null);
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
            throw new UnsupportedOperationException("Unit owner only accepts upgraded messages");
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
