/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;

/** 单订阅的接收流；最多预读一条消息，用户回调在工作线程按序执行。 */
final class GrpcInboundMessagePublisher implements Flow.Publisher<Object>, Flow.Subscription {
    private final GrpcStreamingCall               call;
    private final Callable<Object>                next;
    private final Runnable                        complete;
    private       Flow.Subscriber<? super Object> subscriber;
    private       Object                          buffered;
    private       long                            demand;
    private       boolean                         running;
    private       boolean                         ended;
    private       boolean                         cancelled;
    private       boolean                         notified;
    private       boolean                         subscribed;
    private       Throwable                       failure;

    GrpcInboundMessagePublisher(GrpcStreamingCall call, Callable<Object> next, Runnable complete) {
        this.call = call;
        this.next = next;
        this.complete = complete;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Object> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        synchronized (this) {
            if (this.subscriber != null) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    public void request(long n) {
                    }

                    public void cancel() {
                    }
                });

                subscriber.onError(new IllegalStateException("An RPC stream supports one subscriber"));
                return;
            }
            this.subscriber = subscriber;
        }

        subscriber.onSubscribe(this);
        synchronized (this) {
            this.subscribed = true;
        }
        this.wake();
    }

    @Override
    public void request(long n) {
        synchronized (this) {
            if (this.cancelled || this.notified) {
                return;
            }
        }

        if (n <= 0) {
            this.call.fail(new IllegalArgumentException("Flow demand must be positive"));
            return;
        }

        synchronized (this) {
            long total = this.demand + n;
            this.demand = total < 0 ? Long.MAX_VALUE : total;
        }
        this.wake();
    }

    @Override
    public void cancel() {
        synchronized (this) {
            this.cancelled = true;
            this.buffered = null;
        }
        this.call.cancel();
    }

    void fail(Throwable failure) {
        this.recordFailure(failure);
        this.wake();
    }

    synchronized void recordFailure(Throwable failure) {
        if (this.notified) {
            return;
        }
        if (this.failure == null) {
            this.failure = failure;
        }
        this.ended = true;
        this.buffered = null;
    }

    void close() {
        synchronized (this) {
            if (this.ended) {
                return;
            }
        }
        this.fail(new CancellationException("RPC finished before request consumption"));
    }

    void wake() {
        synchronized (this) {
            if (this.running || this.cancelled || this.notified) {
                return;
            }
            this.running = true;
        }
        if (!this.call.execute(this::drain)) {
            // 工作池拒绝后，仍须在订阅方看到失败，不能只有 RPC 内部结束。
            ForkJoinPool.commonPool().execute(this::drain);
        }
    }

    private void drain() {
        try {
            for (; ; ) {
                Flow.Subscriber<? super Object> target;
                Object value;
                Throwable error;
                boolean terminal;
                synchronized (this) {
                    if (this.cancelled) {
                        this.running = false;
                        return;
                    }
                    target = this.subscribed ? this.subscriber : null;
                    value = this.buffered;
                    terminal = this.ended;
                    error = this.failure;
                    if (terminal) {
                        this.running = false;
                        this.notified = target != null;
                    } else if (value != null) {
                        if (target == null || this.demand == 0) {
                            this.running = false;
                            return;
                        }
                        this.buffered = null;
                        if (this.demand != Long.MAX_VALUE) {
                            this.demand--;
                        }
                    }
                }
                if (terminal) {
                    if (target != null) {
                        if (error == null) {
                            target.onComplete();
                        } else {
                            target.onError(error);
                        }
                    }
                    return;
                }
                if (value != null) {
                    target.onNext(value);
                    continue;
                }

                // 预读一条用于辨认 EOF；无需求时不再继续读取或扩大缓存。
                Object next = this.next.call();
                synchronized (this) {
                    if (!this.cancelled && !this.ended) {
                        this.buffered = next;
                        this.ended = next == null;
                    }
                }
                if (next == null) {
                    this.complete.run();
                }
            }
        } catch (Throwable failure) {
            synchronized (this) {
                this.running = false;
            }
            this.call.receiveFailure(failure);
        }
    }
}
