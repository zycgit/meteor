/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;

/** JDK Flow 的数据源及同步逐条消费适配；不创建线程，不管理网络连接。 */
final class MetStreamUtils {
    private MetStreamUtils() {
    }

    /** 每次订阅独立创建迭代器；实现 AutoCloseable 的迭代器在结束、失败或取消后关闭。 */
    static <T> Flow.Publisher<T> fromIterator(Supplier<? extends Iterator<? extends T>> source) {
        Objects.requireNonNull(source, "source");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            IteratorSubscription<T> subscription = new IteratorSubscription<>(source, subscriber);
            subscriber.onSubscribe(subscription);
        };
    }

    /** 将可迭代数据源适配成按需求交付的消息流。 */
    static <T> Flow.Publisher<T> fromIterable(Iterable<? extends T> source) {
        return fromIterator(source::iterator);
    }

    /** 同步处理完一条后再申请下一条；取消返回的 Future 会取消订阅。 */
    static <T> Future<Void> consume(Flow.Publisher<T> source, Consumer<? super T> consumer) {
        ConsumerSubscriber<T> subscriber = new ConsumerSubscriber<>(consumer);
        try {
            source.subscribe(subscriber);
        } catch (Throwable failure) {
            subscriber.onError(failure);
        }
        return subscriber.completion;
    }

    private static final class ConsumerSubscriber<T> implements Flow.Subscriber<T> {
        private final Consumer<? super T>                consumer;
        private final BasicFuture<Void>                  completion   = new BasicFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private ConsumerSubscriber(Consumer<? super T> consumer) {
            this.consumer = Objects.requireNonNull(consumer, "consumer");
            this.completion.onCancel(done -> {
                Flow.Subscription current = this.subscription.get();
                if (current != null) {
                    current.cancel();
                }
            });
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!this.subscription.compareAndSet(null, subscription) || this.completion.isDone()) {
                subscription.cancel();
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onNext(T item) {
            if (this.completion.isDone()) {
                return;
            }

            try {
                this.consumer.accept(item);
                if (!this.completion.isDone()) {
                    this.subscription.get().request(1);
                }
            } catch (Throwable failure) {
                this.completion.failed(failure);
                this.subscription.get().cancel();
            }
        }

        @Override
        public void onError(Throwable failure) {
            this.completion.failed(failure);
        }

        @Override
        public void onComplete() {
            this.completion.completed(null);
        }
    }

    private static final class IteratorSubscription<T> implements Flow.Subscription {
        private final Supplier<? extends Iterator<? extends T>> source;
        private final Flow.Subscriber<? super T>                subscriber;
        private       Iterator<? extends T>                     iterator;
        private       long                                      demand;
        private       boolean                                   running;
        private       boolean                                   cancelled;
        private       boolean                                   ended;
        private       Throwable                                 failure;

        private IteratorSubscription(Supplier<? extends Iterator<? extends T>> source, Flow.Subscriber<? super T> subscriber) {
            this.source = source;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            synchronized (this) {
                if (this.ended || this.cancelled) {
                    return;
                }
                if (n <= 0) {
                    this.failure = new IllegalArgumentException("Flow demand must be positive");
                } else {
                    long total = this.demand + n;
                    this.demand = total < 0 ? Long.MAX_VALUE : total;
                }
            }
            this.drain();
        }

        @Override
        public void cancel() {
            synchronized (this) {
                this.cancelled = true;
            }
            this.drain();
        }

        private void drain() {
            synchronized (this) {
                if (this.running || this.ended) {
                    return;
                }
                this.running = true;
            }

            // 单个执行者处理数据源；onNext 中再次 request 不会递归调用 onNext。
            for (; ; ) {
                synchronized (this) {
                    if (this.cancelled || this.failure != null) {
                        break;
                    }
                    if (this.demand == 0) {
                        this.running = false;
                        return;
                    }
                    if (this.demand != Long.MAX_VALUE) {
                        this.demand--;
                    }
                }

                try {
                    if (this.iterator == null) {
                        this.iterator = Objects.requireNonNull(this.source.get(), "iterator");
                    }
                    if (!this.iterator.hasNext()) {
                        break;
                    }

                    T item = Objects.requireNonNull(this.iterator.next(), "Flow item");
                    this.subscriber.onNext(item);
                    if (!this.iterator.hasNext()) {
                        break;
                    }
                } catch (Throwable failure) {
                    synchronized (this) {
                        this.failure = failure;
                    }
                    break;
                }
            }
            this.finish();
        }

        private void finish() {
            Throwable cause;
            boolean notify;
            synchronized (this) {
                this.ended = true;
                cause = this.failure;
                notify = !this.cancelled;
            }

            // 数据源清理不占用订阅锁，任意用户代码的阻塞仍由数据源自身负责。
            if (this.iterator instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    if (cause == null) {
                        cause = failure;
                    } else {
                        cause.addSuppressed(failure);
                    }
                }
            }

            this.iterator = null;
            if (notify) {
                if (cause == null) {
                    this.subscriber.onComplete();
                } else {
                    this.subscriber.onError(cause);
                }
            }
        }
    }
}
