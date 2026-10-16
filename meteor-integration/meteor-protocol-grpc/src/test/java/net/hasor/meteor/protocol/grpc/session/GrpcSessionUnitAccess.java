/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;

/** 仅用于 unit 包访问包内算法；无断言、无场景逻辑，不进入插件产物。 */
public final class GrpcSessionUnitAccess {
    private GrpcSessionUnitAccess() {
    }

    public static ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("grpc", "grpc", options);
    }

    // 客户端策略及其按目标共享的重试令牌。
    public static final class ClientPolicy {
        private final GrpcClientRetryPolicy delegate;

        public ClientPolicy(ProtocolConfig config) {
            this.delegate = new GrpcClientRetryPolicy(config);
        }

        public MethodPolicy method(String path) {
            return new MethodPolicy(this.delegate.method(path));
        }

        public RetryThrottle throttle(String target) {
            return new RetryThrottle(this.delegate.throttle(target));
        }
    }

    public static final class MethodPolicy {
        private final GrpcRetryMethodPolicy delegate;

        private MethodPolicy(GrpcRetryMethodPolicy delegate) {
            this.delegate = delegate;
        }

        public int attempts() {
            return this.delegate.attempts();
        }

        public long initialDelay() {
            return this.delegate.initialDelay();
        }

        public boolean retryable(int code) {
            return this.delegate.retryable(code);
        }
    }

    public static final class RetryThrottle {
        private final GrpcRetryThrottle delegate;

        private RetryThrottle(GrpcRetryThrottle delegate) {
            this.delegate = delegate;
        }

        public boolean failed() {
            return this.delegate.failed();
        }

        public boolean allowsRetry() {
            return this.delegate.allowsRetry();
        }

        public void succeeded() {
            this.delegate.succeeded();
        }

        public boolean sameAs(RetryThrottle other) {
            return this.delegate == other.delegate;
        }
    }

    public static final class MountRuntime {
        private final GrpcMountRuntime delegate;

        public MountRuntime(ProtocolConfig config) {
            this.delegate = new GrpcMountRuntime(config);
        }

        public ExecutorService retain(MetContext context) {
            this.delegate.retain(context);
            return (ExecutorService) this.delegate.workers();
        }

        public void release() {
            this.delegate.release();
        }
    }
}
