/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;

/** 每次向业务源申请一条消息，写入有界 HTTP 正文之后才补充需求。 */
final class GrpcOutbound implements Flow.Subscriber<Object> {
    private final GrpcStreamCall    call;
    private       Flow.Subscription subscription;
    private       Object            buffered;
    private       boolean           requested;
    private       boolean           running;
    private       boolean           ended;
    private       boolean           stopped;
    private       Throwable         failure;

    public GrpcOutbound(GrpcStreamCall call) {
        this.call = call;
    }

    public void start(Flow.Publisher<?> source) {
        this.call.execute(() -> {
            try {
                source.subscribe(this);
            } catch (Throwable failure) {
                this.onError(failure);
            }
        });
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        synchronized (this) {
            if (this.subscription != null || this.stopped) {
                ForkJoinPool.commonPool().execute(subscription::cancel);
                return;
            }

            this.subscription = subscription;
        }
        this.wake();
    }

    @Override
    public void onNext(Object item) {
        synchronized (this) {
            if (this.stopped || this.ended) {
                return;
            }

            if (item == null || !this.requested || this.buffered != null) {
                this.failure = new IllegalStateException("Publisher emitted a null or unrequested message");
                this.ended = true;
            } else {
                this.requested = false;
                this.buffered = item;
            }
        }
        this.wake();
    }

    @Override
    public void onError(Throwable failure) {
        synchronized (this) {
            if (this.ended || this.stopped) {
                return;
            }

            this.failure = failure;
            this.ended = true;
        }
        this.wake();
    }

    @Override
    public void onComplete() {
        synchronized (this) {
            this.ended = true;
        }
        this.wake();
    }

    public void stop() {
        Flow.Subscription current;
        synchronized (this) {
            if (this.stopped) {
                return;
            }

            this.stopped = true;
            this.buffered = null;
            current = this.subscription;
        }
        // 用户实现的取消不能挡住 RPC 超时线程或网络资源释放。
        if (current != null) {
            ForkJoinPool.commonPool().execute(current::cancel);
        }
    }

    private void wake() {
        synchronized (this) {
            if (this.running || this.stopped) {
                return;
            }
            this.running = true;
        }
        this.call.execute(this::drain);
    }

    private void drain() {
        try {
            for (; ; ) {
                Object value;
                Throwable error;
                boolean terminal;
                Flow.Subscription next = null;
                synchronized (this) {
                    if (this.stopped) {
                        this.running = false;
                        return;
                    }

                    value = this.buffered;
                    this.buffered = null;
                    error = this.failure;
                    terminal = this.ended && value == null;
                    if (value == null && !terminal && !this.requested && this.subscription != null) {
                        this.requested = true;
                        next = this.subscription;
                    } else if (value == null && !terminal) {
                        this.running = false;
                        return;
                    }
                }

                // onNext 后紧接 onError 时，已交付的消息先写出，再发送错误 trailers。
                if (error != null && value == null) {
                    this.call.sendFailure(error);
                    return;
                }
                if (terminal) {
                    this.call.finishOutput();
                    return;
                }
                if (value != null) {
                    this.call.writeMessage(value);
                } else {
                    next.request(1);
                }
            }
        } catch (Throwable failure) {
            this.call.sendFailure(failure);
        }
    }
}