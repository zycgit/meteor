/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.hasor.neta.channel.NetChannel;

/** Owns accepted sockets until the protocol layer takes them over. */
final class SocketAdmissionTracker {
    private final Set<NetChannel> channels  = new LinkedHashSet<>();
    private       boolean         accepting = true;

    boolean register(NetChannel channel) {
        boolean registered;
        synchronized (this) {
            registered = this.accepting;
            if (registered) {
                this.channels.add(channel);
            }
        }
        if (!registered) {
            channel.closeNow();
            return false;
        }

        channel.onClose(done -> this.remove(channel));
        if (channel.isClose()) {
            this.remove(channel);
            return false;
        }
        return true;
    }

    <M> ChannelListener<M> transferOnConnected(NetChannel channel, ChannelListener<M> receiver) {
        if (receiver instanceof RouteReceiver<?>) {
            @SuppressWarnings("unchecked") RouteReceiver<M> routed = (RouteReceiver<M>) receiver;
            return new AcceptedRouteReceiver<>(this, channel, routed);
        }
        return new AcceptedListener<>(this, channel, receiver);
    }

    private synchronized boolean transfer(NetChannel channel) {
        return this.accepting && this.channels.remove(channel);
    }

    void reject(NetChannel channel) {
        synchronized (this) {
            this.channels.remove(channel);
        }
        channel.closeNow();
    }

    void suspend() {
        List<NetChannel> closing;
        synchronized (this) {
            this.accepting = false;
            closing = new ArrayList<>(this.channels);
            this.channels.clear();
        }
        for (NetChannel channel : closing) {
            channel.closeNow();
        }
    }

    synchronized void resume() {
        this.accepting = true;
    }

    private synchronized void remove(NetChannel channel) {
        this.channels.remove(channel);
    }

    private static class AcceptedListener<M> implements ChannelListener<M> {
        private final   SocketAdmissionTracker tracker;
        private final   NetChannel             channel;
        protected final ChannelListener<M>     receiver;
        private         boolean                connected;

        private AcceptedListener(SocketAdmissionTracker tracker, NetChannel channel, ChannelListener<M> receiver) {
            this.tracker = tracker;
            this.channel = channel;
            this.receiver = receiver;
        }

        @Override
        public void connected() {
            this.connected = this.tracker.transfer(this.channel);
            if (this.connected) {
                this.receiver.connected();
            }
        }

        @Override
        public void receive(M message) throws Exception {
            if (this.connected) {
                this.receiver.receive(message);
            }
        }

        protected final boolean isConnected() {
            return this.connected;
        }

        @Override
        public void closed(Throwable cause) {
            this.receiver.closed(cause);
        }
    }

    private static final class AcceptedRouteReceiver<M> extends AcceptedListener<M> implements RouteReceiver<M> {
        private final RouteReceiver<M> routed;

        private AcceptedRouteReceiver(SocketAdmissionTracker tracker, NetChannel channel, RouteReceiver<M> routed) {
            super(tracker, channel, routed);
            this.routed = routed;
        }

        @Override
        public void receive(String mountId, M message) throws Exception {
            if (this.isConnected()) {
                this.routed.receive(mountId, message);
            }
        }
    }
}
