/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectInboundMessagePublisherTest {
    @Test
    public void noDemandDoesNotDeliverPrefetchedMessage() throws Exception {
        // 响应正文已完整到达，订阅者尚未提供消费需求。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            Flow.Publisher<Object> publisher = test.response("first", "second");
            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            test.runTasks();

            // 允许内部预读，不允许越过 demand 向业务交付消息。
            assertNotNull(subscriber.subscription);
            assertTrue(subscriber.values.isEmpty());
            assertEquals(0, subscriber.completed);
            assertFalse(test.call.completion().isDone());
        }
    }

    @Test
    public void eachDemandReleasesOnlyOneMessage() throws Exception {
        // 接收两条消息，逐次申请一条。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            Flow.Publisher<Object> publisher = test.response("first", "second");
            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            subscriber.subscription.request(1);
            test.runTasks();
            assertEquals(List.of("first"), subscriber.values);
            assertEquals(0, subscriber.completed);

            // 第二个需求释放剩余消息，结束信封无需额外需求即可通知完成。
            subscriber.subscription.request(1);
            test.runTasks();
            assertEquals(List.of("first", "second"), subscriber.values);
            assertEquals(1, subscriber.completed);
            assertTrue(test.call.completion().isDone());
            assertNull(test.call.completion().getCause());
        }
    }

    @Test
    public void zeroDemandFailsTheCall() throws Exception {
        // 建立接收订阅后提交非法的零需求。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            test.response("message").subscribe(subscriber);
            subscriber.subscription.request(0);
            test.runTasks();

            // 同一个失败交付订阅者，并释放调用拥有的 HTTP 交换。
            assertTrue(subscriber.failure instanceof IllegalArgumentException);
            assertSame(subscriber.failure, test.call.completion().getCause());
            assertSame(subscriber.failure, test.exchange.completion().getCause());
            assertTrue(subscriber.values.isEmpty());
        }
    }

    @Test
    public void negativeDemandFailsTheCall() throws Exception {
        // 负数需求同样违反 Flow 契约，不能解释为无限需求。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            test.response("message").subscribe(subscriber);
            subscriber.subscription.request(-1);
            test.runTasks();

            // 请求与订阅者看到相同的参数错误。
            assertTrue(subscriber.failure instanceof IllegalArgumentException);
            assertSame(subscriber.failure, test.request.completion().getCause());
            assertEquals(0, subscriber.completed);
        }
    }

    @Test
    public void secondSubscriberDoesNotReplaceTheFirst() throws Exception {
        // 同一响应流已经绑定首个订阅者。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            Flow.Publisher<Object> publisher = test.response("message");
            RecordingSubscriber first = new RecordingSubscriber();
            publisher.subscribe(first);
            RecordingSubscriber second = new RecordingSubscriber();
            publisher.subscribe(second);
            assertNotNull(second.subscription);
            assertTrue(second.failure instanceof IllegalStateException);

            // 拒绝第二个订阅不影响首个订阅继续消费。
            first.subscription.request(1);
            test.runTasks();
            assertEquals(List.of("message"), first.values);
            assertEquals(1, first.completed);
            assertNull(first.failure);
            assertTrue(second.values.isEmpty());
        }
    }

    @Test
    public void cancellingTheSubscriberReleasesTheExchange() throws Exception {
        // 尚有未消费消息时取消接收订阅。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            test.response("message").subscribe(subscriber);
            subscriber.subscription.cancel();
            test.runTasks();

            // 取消立即结束调用和输出方向，不再向主动取消者发送信号。
            Throwable failure = test.call.completion().getCause();
            assertTrue(failure instanceof CancellationException);
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, test.call.output().completion().getCause().getCause());
            assertSame(failure, test.request.completion().getCause());
            assertTrue(subscriber.values.isEmpty());
            assertNull(subscriber.failure);
            assertEquals(0, subscriber.completed);
        }
    }

    @Test
    public void overflowingDemandBecomesUnbounded() throws Exception {
        // 累加需求超过 long 上限时，应保留无限需求语义。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            test.response("first", "second").subscribe(subscriber);
            subscriber.subscription.request(Long.MAX_VALUE);
            subscriber.subscription.request(1);
            test.runTasks();

            // 所有消息正常交付，不能因数值溢出停在负需求。
            assertEquals(List.of("first", "second"), subscriber.values);
            assertEquals(1, subscriber.completed);
            assertNull(subscriber.failure);
        }
    }

    @Test
    public void callbackFailureTerminatesTheCall() throws Exception {
        // 模拟用户消费回调抛出异常。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            IllegalStateException failure = new IllegalStateException("consumer failed");
            RecordingSubscriber subscriber = new RecordingSubscriber() {
                @Override
                public void onNext(Object item) {
                    throw failure;
                }
            };
            test.response("message").subscribe(subscriber);
            subscriber.subscription.request(1);
            test.runTasks();

            // 原始回调异常终止交换并回送给订阅者，不能遗留挂起调用。
            assertSame(failure, test.call.completion().getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, subscriber.failure);
        }
    }

    @Test
    public void nullSubscriberIsRejectedWithoutConsumingTheStream() throws Exception {
        // 空订阅者应立即报错，正常订阅能力仍然保留。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            Flow.Publisher<Object> publisher = test.response("message");
            assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            subscriber.subscription.request(1);
            test.runTasks();

            // 首个有效订阅仍获得完整响应。
            assertEquals(List.of("message"), subscriber.values);
            assertEquals(1, subscriber.completed);
        }
    }

    @Test
    public void emptyResponseCompletesWithoutDemand() throws Exception {
        // 只有结束信封的响应不需要业务再申请一条不存在的消息。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newClient()) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            test.response().subscribe(subscriber);
            test.runTasks();

            // 晚于 EOF 建立的订阅也能收到唯一完成通知。
            assertEquals(1, subscriber.completed);
            assertTrue(subscriber.values.isEmpty());
            assertNull(subscriber.failure);
            assertTrue(test.call.completion().isDone());
        }
    }

    private static class RecordingSubscriber implements Flow.Subscriber<Object> {
        private final List<Object>      values = new ArrayList<>();
        private       Flow.Subscription subscription;
        private       Throwable         failure;
        private       int               completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(Object item) {
            this.values.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            this.failure = throwable;
        }

        @Override
        public void onComplete() {
            this.completed++;
        }
    }
}
