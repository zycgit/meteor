/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;

/**
 * Holds transport lifecycle events until the channel listener has been created.
 */
public final class ChannelListenerLifecycle<M> implements RouteReceiver<M> {
    private final Runnable           terminated;
    private       ChannelListener<M> listener;
    private       State              state = State.INITIALIZING;
    private       Throwable          failure;
    private       boolean            listenerFailed;
    private       boolean            terminationDelivered;

    private enum State {
        INITIALIZING,
        CONNECTED,
        CLOSED
    }

    public ChannelListenerLifecycle(Runnable terminated) {
        this.terminated = terminated;
    }

    /** Attach the listener after transport lifecycle observation and message routing are ready. */
    public void attach(ChannelListener<M> listener) {
        State current;
        Throwable closedCause;
        synchronized (this) {
            if (this.listener != null || this.listenerFailed) {
                throw new IllegalStateException("Channel listener has already been resolved");
            }
            this.listener = listener;
            current = this.state;
            closedCause = this.failure;
        }

        if (current == State.CONNECTED) {
            listener.connected();
        } else if (current == State.CLOSED) {
            this.notifyClosed(listener, closedCause);
        }
    }

    /** Mark listener construction as failed; termination still waits for the physical close event. */
    public void listenerFailed() {
        boolean finish;
        synchronized (this) {
            this.listenerFailed = true;
            finish = this.state == State.CLOSED;
        }
        if (finish) {
            this.notifyTerminated();
        }
    }

    @Override
    public void connected() {
        ChannelListener<M> attached;
        synchronized (this) {
            if (this.state != State.INITIALIZING) {
                return;
            }
            this.state = State.CONNECTED;
            attached = this.listener;
        }
        if (attached != null) {
            attached.connected();
        }
    }

    @Override
    public void receive(M message) throws Exception {
        ChannelListener<M> attached = this.requireListener();
        attached.receive(message);
    }

    @Override
    public void receive(String mountId, M message) throws Exception {
        ChannelListener<M> attached = this.requireListener();
        if (attached instanceof RouteReceiver<?>) {
            @SuppressWarnings("unchecked") RouteReceiver<M> routed = (RouteReceiver<M>) attached;
            routed.receive(mountId, message);
        } else {
            attached.receive(message);
        }
    }

    @Override
    public void closed(Throwable cause) {
        ChannelListener<M> attached;
        boolean finish;
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.CLOSED;
            this.failure = cause;
            attached = this.listener;
            finish = this.listenerFailed;
        }

        if (attached != null) {
            this.notifyClosed(attached, cause);
        } else if (finish) {
            this.notifyTerminated();
        }
    }

    private synchronized ChannelListener<M> requireListener() {
        if (this.listener == null || this.state == State.CLOSED) {
            throw new IllegalStateException("Channel listener is not available");
        }
        return this.listener;
    }

    private void notifyClosed(ChannelListener<M> attached, Throwable cause) {
        try {
            attached.closed(cause);
        } finally {
            this.notifyTerminated();
        }
    }

    private void notifyTerminated() {
        synchronized (this) {
            if (this.terminationDelivered) {
                return;
            }
            this.terminationDelivered = true;
        }
        this.terminated.run();
    }
}
