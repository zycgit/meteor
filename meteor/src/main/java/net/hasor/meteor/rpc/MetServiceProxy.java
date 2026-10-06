/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;

/** Routes business methods through the client; Object methods stay local. */
final class MetServiceProxy implements InvocationHandler {
    private final MetClient      client;
    private final MetBindInfo<?> bindInfo;

    public MetServiceProxy(MetClient client, MetBindInfo<?> bindInfo) {
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
                    return "MetServiceProxy[" + this.bindInfo.getBindID() + "]";
            }
        }
        return this.client.syncInvoke(this.bindInfo, method.getName(), method.getParameterTypes(), arguments);
    }
}
