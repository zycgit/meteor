/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.domain.MetTimeoutException;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

/** 在既有 Meteor 客户端上保留 Thrift 声明异常；不创建或管理连接。 */
public final class ThriftClient {
    private final MetClient client;

    public ThriftClient(MetClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** 通过绑定的普通 Java 接口调用服务，声明异常不再包在 Future 的 ExecutionException 中。 */
    public <T> T getRemote(MetBindInfo<T> service) {
        Class<T> contract = service.getBindType();
        InvocationHandler calls = (target, method, args) -> this.invoke(target, service, method, args);
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract }, calls);
        return contract.cast(proxy);
    }

    private Object invoke(Object target, MetBindInfo<?> service, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> target == args[0];
                case "hashCode" -> System.identityHashCode(target);
                case "toString" -> "ThriftClient[" + service.getBindID() + "]";
                default -> throw new IllegalStateException(method.getName());
            };
        }

        // RPC、超时和请求生命周期继续由已有客户端处理，仅转换调用方可见的异常契约。
        try {
            Object[] arguments = args == null ? new Object[0] : args;
            return this.client.syncInvoke(service, method.getName(), method.getParameterTypes(), arguments);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw this.failure(cause == null ? failure : cause, method);
        } catch (InterruptedException | TimeoutException | RuntimeException failure) {
            throw this.failure(failure, method);
        }
    }

    private Throwable failure(Throwable cause, Method method) {
        if (cause instanceof TException || cause instanceof Error) {
            return cause;
        }

        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isInstance(cause)) {
                return cause;
            }
        }

        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new TTransportException("Thrift call interrupted", cause);
        }
        if (cause instanceof MetTimeoutException || cause instanceof TimeoutException) {
            return new TTransportException(TTransportException.TIMED_OUT, "Thrift call timed out", cause);
        }
        if (cause instanceof IOException || cause instanceof CancellationException) {
            return new TTransportException("Thrift transport failed", cause);
        }

        String message = cause.getMessage();
        TApplicationException application = new TApplicationException(TApplicationException.INTERNAL_ERROR, message);
        application.initCause(cause);
        return application;
    }
}