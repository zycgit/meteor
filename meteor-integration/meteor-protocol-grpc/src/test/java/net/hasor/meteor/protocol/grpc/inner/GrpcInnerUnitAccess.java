/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.inner;

import java.util.concurrent.Flow;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;

/** 仅用于 unit 包访问标准服务的包内实现；无断言、无场景逻辑，不进入插件产物。 */
public final class GrpcInnerUnitAccess {
    private GrpcInnerUnitAccess() {
    }

    // 健康状态订阅：将释放回调转换为可观察的服务名。
    public static final class HealthWatch implements Flow.Subscription {
        private final GrpcHealthWatch delegate;

        public HealthWatch(String name, Flow.Subscriber<? super JsonNode> subscriber, Consumer<String> closed) {
            this.delegate = new GrpcHealthWatch(name, subscriber, watch -> closed.accept(watch.name()));
        }

        public void request(long count) {
            this.delegate.request(count);
        }

        public void cancel() {
            this.delegate.cancel();
        }

        public void update(String status) {
            this.delegate.update(status);
        }

        public void close() {
            this.delegate.close();
        }
    }
}
