/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfClient;

/** Routes business methods through the client; Object methods stay local. */
final class RsfServiceProxy implements InvocationHandler {
    private final RsfClient      client;
    private final RsfBindInfo<?> bindInfo;

    public RsfServiceProxy(RsfClient client, RsfBindInfo<?> bindInfo) {
        this.client = client;
        this.bindInfo = bindInfo;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return proxy == arguments[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "RsfServiceProxy[" + this.bindInfo.getBindID() + "]";
            }
        }
        return this.client.syncInvoke(this.bindInfo, method.getName(), method.getParameterTypes(), arguments);
    }
}
