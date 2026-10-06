/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.CancelFutureCallback;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.domain.MetRequestObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client facade with a fixed address provider; the host owns the shared caller's lifecycle. */
public final class MetClientImpl implements MetClient {
    private static final Logger          logger = LoggerFactory.getLogger(MetClientImpl.class);
    private final        MetCaller       caller;
    private final        AddressProvider provider;

    public MetClientImpl(MetCaller caller, AddressProvider provider) {
        this.caller = caller;
        this.provider = provider;
    }

    @Override
    public <T> T getRemoteByID(String serviceID) {
        MetBindInfo<T> service = this.caller.getContext().getServiceInfo(serviceID);
        return this.getRemote(this.requireService(service, serviceID));
    }

    @Override
    public <T> T getRemote(String group, String name, String version) {
        MetBindInfo<T> service = this.caller.getContext().getServiceInfo(group, name, version);
        return this.getRemote(this.requireService(service, "[" + group + "]" + name + "-" + version));
    }

    @Override
    public <T> T getRemote(MetBindInfo<T> bindInfo) {
        return this.wrapper(bindInfo, bindInfo.getBindType());
    }

    @Override
    public <T> T wrapperByID(String serviceID, Class<T> interFace) {
        MetBindInfo<?> service = this.caller.getContext().getServiceInfo(serviceID);
        return this.wrapper(this.requireService(service, serviceID), interFace);
    }

    @Override
    public <T> T wrapper(Class<T> interFace) {
        MetBindInfo<T> service = this.caller.getContext().getServiceInfo(interFace);
        return this.wrapper(this.requireService(service, interFace.getName()), interFace);
    }

    @Override
    public <T> T wrapper(String group, String name, String version, Class<T> interFace) {
        MetBindInfo<?> service = this.caller.getContext().getServiceInfo(group, name, version);
        return service == null ? null : this.wrapper(service, interFace);
    }

    @Override
    public <T> T wrapper(MetBindInfo<?> bindInfo, Class<T> interFace) {
        Objects.requireNonNull(bindInfo, "bindInfo");
        if (!interFace.isInterface()) {
            throw new UnsupportedOperationException("interFace " + interFace.getName() + " must be an interface");
        }
        Object proxy = Proxy.newProxyInstance(this.caller.getContext().getClassLoader(), new Class<?>[] { interFace }, new MetServiceProxy(this, bindInfo));
        return interFace.cast(proxy);
    }

    @Override
    public Object syncInvoke(MetBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) throws InterruptedException, ExecutionException, TimeoutException {
        MetFuture future = this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects);
        int timeout = future.getRequest().getTimeout();
        if (timeout <= 0) {
            timeout = this.caller.getContext().getSettings().getDefaultTimeout();
        }
        return future.get(timeout, TimeUnit.MILLISECONDS).getData();
    }

    @Override
    public MetFuture asyncInvoke(MetBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) {
        MetRequestObject request = this.caller.createRequest(this.provider, bindInfo, methodName, parameterTypes, parameterObjects);
        return this.caller.invoke(request);
    }

    @Override
    public void callbackInvoke(MetBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects, FutureCallback<Object> listener) {
        this.notifyCallback(this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects), listener, MetResponse::getData);
    }

    @Override
    public void callbackRequest(MetBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects, FutureCallback<MetResponse> listener) {
        this.notifyCallback(this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects), listener, Function.identity());
    }

    private <T> MetBindInfo<T> requireService(MetBindInfo<T> service, String name) {
        if (service == null) {
            throw new IllegalStateException("Service " + name + " is undefined");
        }
        return service;
    }

    private <T> void notifyCallback(MetFuture future, FutureCallback<T> listener, Function<MetResponse, T> result) {
        if (listener == null) {
            return;
        }

        future.onFinal(done -> {
            try {
                if (done.isCancelled()) {
                    if (listener instanceof CancelFutureCallback) {
                        ((CancelFutureCallback<T>) listener).cancelled();
                    } else {
                        listener.failed(new CancellationException("RPC cancelled"));
                    }
                } else if (done.getCause() != null) {
                    listener.failed(done.getCause());
                } else {
                    listener.completed(result.apply(done.getResult()));
                }
            } catch (RuntimeException failure) {
                logger.warn("RPC callback failed", failure);
            }
        });
    }
}