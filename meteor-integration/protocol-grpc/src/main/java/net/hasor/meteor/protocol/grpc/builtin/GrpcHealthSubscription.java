/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.builtin;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;

/** 单订阅、有界的最新状态通知；慢消费者只保留最新状态，不累积事件。 */
final class GrpcHealthSubscription implements Flow.Subscription {
    private final String                            name;
    private final Flow.Subscriber<? super JsonNode> subscriber;
    private final Consumer<GrpcHealthSubscription>  release;
    private final AtomicInteger                     draining = new AtomicInteger();
    private       String                            latest;
    private       String                            delivered;
    private       long                              demand;
    private       boolean                           ended;
    private       boolean                           cancelled;
    private       Throwable                         failure;

    GrpcHealthSubscription(String name, Flow.Subscriber<? super JsonNode> subscriber, Consumer<GrpcHealthSubscription> release) {
        this.name = name;
        this.subscriber = subscriber;
        this.release = release;
    }

    String name() {
        return this.name;
    }

    void update(String status) {
        synchronized (this) {
            if (this.cancelled || this.ended) {
                return;
            }
            this.latest = status;
        }
        this.drain();
    }

    @Override
    public void request(long count) {
        synchronized (this) {
            if (count <= 0) {
                this.failure = new IllegalArgumentException("Flow demand must be positive");
                this.ended = true;
            } else {
                long sum = this.demand + count;
                this.demand = sum < 0 ? Long.MAX_VALUE : sum;
            }
        }
        this.drain();
    }

    @Override
    public void cancel() {
        synchronized (this) {
            if (this.cancelled) {
                return;
            }
            this.cancelled = true;
        }
        this.release.accept(this);
    }

    void close() {
        synchronized (this) {
            this.ended = true;
        }
        this.drain();
    }

    private void drain() {
        if (this.draining.getAndIncrement() != 0) {
            return;
        }
        int pending = 1;
        do {
            String next = null;
            Throwable error;
            boolean complete;
            synchronized (this) {
                if (this.cancelled) {
                    return;
                }
                complete = this.ended;
                error = this.failure;
                boolean changed = this.latest != null && !StringUtils.equals(this.latest, this.delivered);
                if (!complete && changed && this.demand > 0) {
                    next = this.latest;
                    this.delivered = next;
                    this.demand--;
                }
                if (complete) {
                    this.cancelled = true;
                }
            }
            try {
                if (complete) {
                    this.release.accept(this);
                    if (error == null) {
                        this.subscriber.onComplete();
                    } else {
                        this.subscriber.onError(error);
                    }
                    return;
                }
                if (next != null) {
                    this.subscriber.onNext(GrpcHealthEndpoint.response(next));
                }
            } catch (Throwable failure) {
                this.cancel();
                return;
            }
            pending = this.draining.addAndGet(-pending);
        } while (pending != 0);
    }
}
