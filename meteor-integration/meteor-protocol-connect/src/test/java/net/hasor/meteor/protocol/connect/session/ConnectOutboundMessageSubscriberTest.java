/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectOutboundMessageSubscriberTest {
    @Test
    public void initialDemandRequestsOneMessage() throws Exception {
        // 业务源只记录需求，暂不提供消息。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscription subscription = new RecordingSubscription(count -> {
            });
            test.source(subscriber -> subscriber.onSubscribe(subscription));
            test.call.startRequest();
            test.runTasks();

            // 出站流只申请一个消息，不提前无限拉取业务数据。
            assertEquals(List.of(1L), subscription.demands);
            assertFalse(test.call.completion().isDone());
            assertEquals(1, subscription.cancelled.getCount());
        }
    }

    @Test
    public void writingOneMessageReplenishesOneDemand() throws Exception {
        // 首次需求立即提供一条消息，后续需求仅记录。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            List<Long> demands = new ArrayList<>();
            test.source(subscriber -> {
                RecordingSubscription subscription = new RecordingSubscription(count -> {
                    demands.add(count);
                    if (demands.size() == 1) {
                        subscriber.onNext("message");
                    }
                });
                subscriber.onSubscribe(subscription);
            });
            test.call.startRequest();
            test.runTasks();

            // 消息写入有界正文之后，发送器才为下一条消息补充需求。
            byte[] expected = test.frame("message");
            assertArrayEquals(expected, test.call.output().readNBytes(expected.length));
            assertEquals(List.of(1L, 1L), demands);
            assertFalse(test.call.completion().isDone());
        }
    }

    @Test
    public void nullMessageFailsAndCancelsTheSource() throws Exception {
        // 业务源收到合法需求后错误地产生 null 消息。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            AtomicReference<RecordingSubscription> subscription = new AtomicReference<>();
            test.source(subscriber -> {
                RecordingSubscription current = new RecordingSubscription(count -> subscriber.onNext(null));
                subscription.set(current);
                subscriber.onSubscribe(current);
            });
            test.call.startRequest();
            test.runTasks();

            // 协议不能序列化 null Flow 消息，失败必须解除上游订阅。
            Throwable failure = test.call.completion().getCause();
            assertTrue(failure instanceof IllegalStateException);
            assertSame(failure, test.exchange.completion().getCause());
            assertTrue(subscription.get().cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void unrequestedMessageFailsTheCall() throws Exception {
        // 业务源在 onSubscribe 之前主动推送消息，没有任何消费许可。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscription subscription = new RecordingSubscription(count -> {
            });
            test.source(subscriber -> {
                subscriber.onNext("unsolicited");
                subscriber.onSubscribe(subscription);
            });
            test.call.startRequest();
            test.runTasks();

            // 未申请的消息终止调用，不能继续从违规源拉取数据。
            assertTrue(test.call.completion().getCause() instanceof IllegalStateException);
            assertTrue(subscription.demands.isEmpty());
            assertTrue(subscription.cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void secondMessageWithoutNewDemandFailsTheCall() throws Exception {
        // 上游在一个 request(1) 回调中连续发出两条消息。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            AtomicReference<RecordingSubscription> subscription = new AtomicReference<>();
            test.source(subscriber -> {
                RecordingSubscription current = new RecordingSubscription(count -> {
                    subscriber.onNext("first");
                    subscriber.onNext("second");
                });
                subscription.set(current);
                subscriber.onSubscribe(current);
            });
            test.call.startRequest();
            test.runTasks();

            // 单消息缓冲不可被第二条覆盖，违约源被取消而不是继续积压。
            assertTrue(test.call.completion().getCause() instanceof IllegalStateException);
            assertEquals(List.of(1L), subscription.get().demands);
            assertTrue(subscription.get().cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void synchronousSubscribeFailureTerminatesTheExchange() throws Exception {
        // 用户 Publisher 的 subscribe 方法直接抛出异常。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            IllegalStateException failure = new IllegalStateException("subscribe failed");
            test.source(subscriber -> {
                throw failure;
            });
            test.call.startRequest();
            test.runTasks();

            // 异常保留原始原因，不能只停掉工作任务而挂住 RPC。
            assertSame(failure, test.call.completion().getCause());
            assertSame(failure, test.request.completion().getCause());
            assertSame(failure, test.exchange.completion().getCause());
        }
    }

    @Test
    public void synchronousRequestFailureCancelsTheSource() throws Exception {
        // 订阅创建成功，但第一次 request 回调抛出异常。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            IllegalStateException failure = new IllegalStateException("request failed");
            RecordingSubscription subscription = new RecordingSubscription(count -> {
                throw failure;
            });
            test.source(subscriber -> subscriber.onSubscribe(subscription));
            test.call.startRequest();
            test.runTasks();

            // 需求回调失败与显式 onError 一样终止调用并取消订阅。
            assertSame(failure, test.call.completion().getCause());
            assertTrue(subscription.cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void duplicateSubscriptionCancelsOnlyTheReplacement() throws Exception {
        // 业务源错误地重复发送 onSubscribe。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscription original = new RecordingSubscription(count -> {
            });
            RecordingSubscription replacement = new RecordingSubscription(count -> {
            });
            test.source(subscriber -> {
                subscriber.onSubscribe(original);
                subscriber.onSubscribe(replacement);
            });
            test.call.startRequest();
            test.runTasks();

            // 保持首次订阅及其消费需求，只取消重复登记的订阅。
            assertEquals(List.of(1L), original.demands);
            assertEquals(1, original.cancelled.getCount());
            assertTrue(replacement.cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(replacement.demands.isEmpty());
            assertFalse(test.call.completion().isDone());
        }
    }

    @Test
    public void sourceErrorPreservesTheOriginalFailure() throws Exception {
        // 已订阅业务源通过 onError 主动报告失败。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            IOException failure = new IOException("source failed");
            RecordingSubscription subscription = new RecordingSubscription(count -> {
            });
            test.source(subscriber -> {
                subscriber.onSubscribe(subscription);
                subscriber.onError(failure);
            });
            test.call.startRequest();
            test.runTasks();

            // 原始故障同时终止交换和正文，订阅资源被取消。
            assertSame(failure, test.call.completion().getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, test.call.output().completion().getCause());
            assertTrue(subscription.cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void sourceCompletionEndsRequestWithoutAnEndStreamEnvelope() throws Exception {
        // 请求源发送一个消息后结束，请求方向以 HTTP EOF 表达完成。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            test.source(subscriber -> subscriber.onSubscribe(new RecordingSubscription(count -> {
                subscriber.onNext("message");
                subscriber.onComplete();
            })));
            test.call.startRequest();
            test.runTasks();

            // 请求正文只包含消息帧；完整 RPC 仍需等待响应方向。
            assertArrayEquals(test.frame("message"), test.call.output().readAllBytes());
            assertFalse(test.call.completion().isDone());
        }
    }

    @Test
    public void callCancellationCancelsThePublisherSubscription() throws Exception {
        // 用户源已订阅且等待生成消息，Caller 取消整个 RPC。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscription subscription = new RecordingSubscription(count -> {
            });
            test.source(subscriber -> subscriber.onSubscribe(subscription));
            test.call.startRequest();
            test.runTasks();
            test.call.cancel();
            test.runTasks();

            // 取消不依赖下一次用户发消息，立即释放交换并异步取消源。
            assertTrue(test.call.completion().getCause() instanceof CancellationException);
            assertTrue(test.exchange.completion().isDone());
            assertTrue(subscription.cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void subscriptionArrivingAfterCancellationIsCancelled() throws Exception {
        // 源保留订阅回调，RPC 先于 onSubscribe 到达而结束。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            AtomicReference<Flow.Subscriber<? super Object>> target = new AtomicReference<>();
            Flow.Publisher<Object> source = target::set;
            test.source(source);
            test.call.startRequest();
            test.runTasks();
            test.call.cancel();

            // 迟到订阅必须取消，不能在结束后重新拉取消息。
            RecordingSubscription subscription = new RecordingSubscription(count -> {
            });
            target.get().onSubscribe(subscription);
            test.runTasks();
            assertTrue(subscription.cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(subscription.demands.isEmpty());
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final LongConsumer   onRequest;
        private final List<Long>     demands   = new ArrayList<>();
        private final CountDownLatch cancelled = new CountDownLatch(1);

        private RecordingSubscription(LongConsumer onRequest) {
            this.onRequest = onRequest;
        }

        @Override
        public void request(long n) {
            this.demands.add(n);
            this.onRequest.accept(n);
        }

        @Override
        public void cancel() {
            this.cancelled.countDown();
        }
    }
}
