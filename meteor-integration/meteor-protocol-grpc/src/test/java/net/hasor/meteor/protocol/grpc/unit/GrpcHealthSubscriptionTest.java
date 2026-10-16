/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.unit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinUnitAccess.HealthSubscription;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GrpcHealthSubscriptionTest {
    @Test
    public void slowWatcherKeepsOnlyTheLatestStatus() throws Exception {
        // 创建尚未申请额度的健康状态订阅。
        Recording subscriber = new Recording();
        AtomicInteger released = new AtomicInteger();
        Consumer<String> onClosed = ignored -> released.incrementAndGet();
        HealthSubscription watch = new HealthSubscription("example.Service", subscriber, onClosed);
        subscriber.onSubscribe(watch);
        watch.update("SERVICE_UNKNOWN");
        assertTrue(subscriber.values.isEmpty());

        // 首次申请额度时才收到当前状态。
        watch.request(1);
        assertEquals(List.of("SERVICE_UNKNOWN"), subscriber.values);

        // 没有新额度时，反复上下线只保存最新值，不累积事件。
        for (int index = 0; index < 1000; index++) {
            watch.update("NOT_SERVING");
            watch.update("SERVING");
        }
        watch.request(1);
        assertEquals(List.of("SERVICE_UNKNOWN", "SERVING"), subscriber.values);
    }

    @Test
    public void cancellingTwiceReleasesWatcherOnce() throws Exception {
        // 创建记录释放次数的健康状态订阅。
        Recording subscriber = new Recording();
        AtomicInteger released = new AtomicInteger();
        Consumer<String> onClosed = ignored -> released.incrementAndGet();
        HealthSubscription watch = new HealthSubscription("example.Service", subscriber, onClosed);

        // 重复取消只释放一次，取消后的状态更新不再交付。
        watch.request(1);
        watch.cancel();
        watch.cancel();
        watch.update("NOT_SERVING");
        assertEquals(1, released.get());
        assertTrue(subscriber.values.isEmpty());
    }

    @Test
    public void invalidDemandTerminatesAndReleasesWatcher() throws Exception {
        // 创建用于接收终止异常的订阅者。
        Recording invalid = new Recording();
        AtomicInteger released = new AtomicInteger();
        HealthSubscription watch = new HealthSubscription("", invalid, ignored -> released.incrementAndGet());

        // 申请零额度触发错误并释放观察者。
        watch.request(0);
        assertTrue(invalid.failure instanceof IllegalArgumentException);
        assertEquals(1, released.get());
    }

    @Test
    public void shutdownTerminatesWithoutDemand() throws Exception {
        // 创建尚未申请额度的健康状态订阅。
        AtomicInteger released = new AtomicInteger();
        Recording closed = new Recording();
        HealthSubscription closing = new HealthSubscription("", closed, ignored -> released.incrementAndGet());

        // 关闭不依赖消息额度，立即完成订阅并释放观察者。
        closing.close();
        assertTrue(closed.completed);
        assertEquals(1, released.get());
    }

    private static final class Recording implements Flow.Subscriber<JsonNode> {
        private final List<String> values = new ArrayList<>();
        private       Throwable    failure;
        private       boolean      completed;

        public void onSubscribe(Flow.Subscription subscription) {
        }

        public void onNext(JsonNode value) {
            this.values.add(value.get("status").asText());
        }

        public void onError(Throwable failure) {
            this.failure = failure;
        }

        public void onComplete() {
            this.completed = true;
        }
    }
}
