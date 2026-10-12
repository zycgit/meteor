/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Flow;

/** 一个 Java 方法对应一条请求消息序列和一条响应消息序列。 */
final class GrpcMethod {
    private final Method   method;
    private final Class<?> requestType;
    private final Class<?> responseType;

    public GrpcMethod(Method method) {
        if (method.getParameterCount() != 1 || method.getReturnType() == void.class) {
            throw new IllegalArgumentException("gRPC requires one request and one response type: " + method);
        }
        this.method = method;
        this.requestType = this.messageType(method.getGenericParameterTypes()[0]);
        this.responseType = this.messageType(method.getGenericReturnType());
    }

    public Method method() {
        return this.method;
    }

    public Class<?> requestType() {
        return this.requestType;
    }

    public Class<?> responseType() {
        return this.responseType;
    }

    public boolean streamingRequest() {
        return this.method.getParameterTypes()[0] == Flow.Publisher.class;
    }

    public boolean streamingResponse() {
        return this.method.getReturnType() == Flow.Publisher.class;
    }

    public boolean streaming() {
        return this.streamingRequest() || this.streamingResponse();
    }

    private Class<?> messageType(Type type) {
        if (type instanceof ParameterizedType parameter && parameter.getRawType() == Flow.Publisher.class) {
            type = parameter.getActualTypeArguments()[0];
        }
        if (!(type instanceof Class<?> value) || Flow.Publisher.class.isAssignableFrom(value)) {
            throw new IllegalArgumentException("gRPC requires a concrete message type or Flow.Publisher<T>: " + type);
        }

        boolean input = InputStream.class.isAssignableFrom(value) || Reader.class.isAssignableFrom(value);
        boolean output = OutputStream.class.isAssignableFrom(value) || Writer.class.isAssignableFrom(value);
        if (input || output || value == Object.class) {
            throw new IllegalArgumentException("gRPC messages must be values; use Flow.Publisher<T> for streaming");
        } else {
            return value;
        }
    }
}
