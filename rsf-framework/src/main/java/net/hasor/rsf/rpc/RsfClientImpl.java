/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.CancelFutureCallback;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfClient;
import net.hasor.rsf.RsfFuture;
import net.hasor.rsf.RsfResponse;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.domain.RsfRequestObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client facade with a fixed address provider; the host owns the shared caller's lifecycle. */
public final class RsfClientImpl implements RsfClient {
    private static final Logger          logger = LoggerFactory.getLogger(RsfClientImpl.class);
    private final        RsfCaller       caller;
    private final        AddressProvider provider;

    public RsfClientImpl(RsfCaller caller, AddressProvider provider) {
        this.caller = caller;
        this.provider = provider;
    }

    @Override
    public <T> T getRemoteByID(String serviceID) {
        RsfBindInfo<T> service = this.caller.getContext().getServiceInfo(serviceID);
        return this.getRemote(this.requireService(service, serviceID));
    }

    @Override
    public <T> T getRemote(String group, String name, String version) {
        RsfBindInfo<T> service = this.caller.getContext().getServiceInfo(group, name, version);
        return this.getRemote(this.requireService(service, "[" + group + "]" + name + "-" + version));
    }

    @Override
    public <T> T getRemote(RsfBindInfo<T> bindInfo) {
        return this.wrapper(bindInfo, bindInfo.getBindType());
    }

    @Override
    public <T> T wrapperByID(String serviceID, Class<T> interFace) {
        RsfBindInfo<?> service = this.caller.getContext().getServiceInfo(serviceID);
        return this.wrapper(this.requireService(service, serviceID), interFace);
    }

    @Override
    public <T> T wrapper(Class<T> interFace) {
        RsfBindInfo<T> service = this.caller.getContext().getServiceInfo(interFace);
        return this.wrapper(this.requireService(service, interFace.getName()), interFace);
    }

    @Override
    public <T> T wrapper(String group, String name, String version, Class<T> interFace) {
        RsfBindInfo<?> service = this.caller.getContext().getServiceInfo(group, name, version);
        return service == null ? null : this.wrapper(service, interFace);
    }

    @Override
    public <T> T wrapper(RsfBindInfo<?> bindInfo, Class<T> interFace) {
        Objects.requireNonNull(bindInfo, "bindInfo");
        if (!interFace.isInterface()) {
            throw new UnsupportedOperationException("interFace " + interFace.getName() + " must be an interface");
        }
        Object proxy = Proxy.newProxyInstance(this.caller.getContext().getClassLoader(), new Class<?>[] { interFace }, new RsfServiceProxy(this, bindInfo));
        return interFace.cast(proxy);
    }

    @Override
    public Object syncInvoke(RsfBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) throws InterruptedException, ExecutionException, TimeoutException {
        RsfFuture future = this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects);
        int timeout = future.getRequest().getTimeout();
        if (timeout <= 0) {
            timeout = this.caller.getContext().getSettings().getDefaultTimeout();
        }
        return future.get(timeout, TimeUnit.MILLISECONDS).getData();
    }

    @Override
    public RsfFuture asyncInvoke(RsfBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) {
        RsfRequestObject request = this.caller.createRequest(this.provider, bindInfo, methodName, parameterTypes, parameterObjects);
        return this.caller.invoke(request);
    }

    @Override
    public void callbackInvoke(RsfBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects, FutureCallback<Object> listener) {
        this.notifyCallback(this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects), listener, RsfResponse::getData);
    }

    @Override
    public void callbackRequest(RsfBindInfo<?> bindInfo, String methodName, Class<?>[] parameterTypes, Object[] parameterObjects, FutureCallback<RsfResponse> listener) {
        this.notifyCallback(this.asyncInvoke(bindInfo, methodName, parameterTypes, parameterObjects), listener, Function.identity());
    }

    private <T> RsfBindInfo<T> requireService(RsfBindInfo<T> service, String name) {
        if (service == null) {
            throw new IllegalStateException("Service " + name + " is undefined");
        }
        return service;
    }

    private <T> void notifyCallback(RsfFuture future, FutureCallback<T> listener, Function<RsfResponse, T> result) {
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