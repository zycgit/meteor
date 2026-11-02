/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.WildcardType;
import java.util.concurrent.Flow;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ParameterMeta;

/** 已发布 Java 方法的参数列表及直接 Flow 消息类型。 */
public final class TripleMethodShape {
    private final Method   method;
    private final Class<?> requestType;
    private final Class<?> responseType;

    public TripleMethodShape(MethodMeta description) {
        Method method = description.getJavaMethod();
        this.method = method;
        boolean publisher = false;
        for (ParameterMeta parameter : description.getParameters()) {
            Class<?> type = parameter.getType().resolve(Object.class);
            publisher |= Flow.Publisher.class.isAssignableFrom(type);
            rejectStream(type);
        }
        if (publisher && (method.getParameterCount() != 1 || method.getParameterTypes()[0] != Flow.Publisher.class)) {
            throw new IllegalArgumentException("Triple request Flow must be the sole direct parameter: " + method);
        }
        this.requestType = publisher ? element(description.getParameters().get(0).getType()) : Object[].class;
        Class<?> result = description.getReturnType().resolve(Object.class);
        if (Flow.Publisher.class.isAssignableFrom(result) && !this.streamingResponse()) {
            String message = "Triple response Flow must use the direct Publisher interface: " + method;
            throw new IllegalArgumentException(message);
        }
        this.responseType = this.streamingResponse() ? element(description.getReturnType()) //
                : description.getReturnType().resolve(Object.class);
        rejectStream(this.responseType);
    }

    /** 发布期判断签名是否可表达，不把协议不支持转换为应用启动异常。 */
    public static boolean supports(MethodMeta method) {
        try {
            new TripleMethodShape(method);
            return true;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
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
        return this.method.getParameterCount() == 1 && this.method.getParameterTypes()[0] == Flow.Publisher.class;
    }

    public boolean streamingResponse() {
        return this.method.getReturnType() == Flow.Publisher.class;
    }

    public boolean streaming() {
        return this.streamingRequest() || this.streamingResponse();
    }

    private static Class<?> element(ResolvableType publisher) {
        ResolvableType value = publisher.getGeneric(0);
        Class<?> concrete = value.resolve();
        boolean complex = value.hasGenerics() || value.getType() instanceof WildcardType;
        if (!complex && concrete != null && concrete != Object.class && concrete != Flow.Publisher.class) {
            rejectStream(concrete);
            return concrete;
        }
        throw new IllegalArgumentException("Triple Flow requires a concrete message type: " + publisher);
    }

    private static boolean isStream(Class<?> type) {
        boolean input = InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        boolean output = OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type);
        return input || output;
    }

    private static void rejectStream(Class<?> type) {
        if (isStream(type)) {
            throw new IllegalArgumentException("Triple messages use values or Flow.Publisher<T>, not I/O streams");
        }
    }
}
