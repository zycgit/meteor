/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 仅用于 unit 包访问包内算法；无断言、无场景逻辑，不进入插件产物。 */
public final class GrpcUnitAccess {
    private GrpcUnitAccess() {
    }

    // 编解码入口：仅转发到插件包内实现。
    public static final class Codec {
        private final GrpcCodec delegate;

        public Codec(ProtocolConfig config, MetContext context) {
            this.delegate = new GrpcCodec(config, context);
        }

        public RequestPayload receive(HttpRequest request) throws Exception {
            return this.delegate.receive(request);
        }

        public HttpRequest encode(RequestPayload request) throws Exception {
            return this.delegate.encode(request);
        }

        public HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
            return this.delegate.encode(request, response);
        }

        public ResponsePayload decode(RequestPayload request, HttpResponse response) throws Exception {
            return this.delegate.decode(request, response);
        }

        public byte[] frame(byte[] message, String encoding) throws Exception {
            return this.delegate.frame(message, encoding);
        }

        public byte[] readMessage(InputStream input, String encoding) throws Exception {
            return this.delegate.readMessage(input, encoding);
        }

        public Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, //
                boolean parameter) throws Exception {
            return this.delegate.decodeValue(request, type, bytes, parameter);
        }

        public void validateStatus(HttpResponse response, Map<String, List<String>> trailers) throws Exception {
            this.delegate.validateStatus(response, trailers);
        }

        public String requestCompression() {
            return this.delegate.requestCompression();
        }

        public HttpResponse error(Throwable failure) {
            return this.delegate.error(failure);
        }
    }

    // Java 方法签名解析结果。
    public static final class MethodShape {
        private final GrpcMethod delegate;

        public MethodShape(Method method) {
            this.delegate = new GrpcMethod(method);
        }

        public boolean streamingRequest() {
            return this.delegate.streamingRequest();
        }

        public boolean streamingResponse() {
            return this.delegate.streamingResponse();
        }

        public Class<?> requestType() {
            return this.delegate.requestType();
        }

        public Class<?> responseType() {
            return this.delegate.responseType();
        }
    }

    public static void receiveMetadata(GrpcMetadata metadata, Map<String, List<String>> values, boolean trailer) {
        metadata.receive(values, trailer);
    }

    // 客户端策略及其按目标共享的重试令牌。
    public static final class ClientPolicy {
        private final GrpcClientPolicy delegate;

        public ClientPolicy(ProtocolConfig config) {
            this.delegate = new GrpcClientPolicy(config);
        }

        public MethodPolicy method(String path) {
            return new MethodPolicy(this.delegate.method(path));
        }

        public RetryThrottle throttle(String target) {
            return new RetryThrottle(this.delegate.throttle(target));
        }
    }

    public static final class MethodPolicy {
        private final GrpcMethodPolicy delegate;

        private MethodPolicy(GrpcMethodPolicy delegate) {
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
    }
}
