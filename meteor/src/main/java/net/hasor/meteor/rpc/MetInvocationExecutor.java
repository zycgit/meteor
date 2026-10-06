/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.ProtocolStatus;

/**
 * 负责处理服务的调用。
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetInvocationExecutor implements MetFilterChain {
    //default invoke
    public void doFilter(MetRequest request, MetResponse response) throws Throwable {
        if (response.isResponse()) {
            return;
        }

        MetBindInfo<?> bindInfo = request.getBindInfo();
        Supplier<?> targetProvider = request.getContext().getServiceProvider(bindInfo);
        Object target = targetProvider == null ? null : targetProvider.get();

        if (target == null) {
            response.sendStatus(ProtocolStatus.NotFound, "service " + bindInfo.getBindID() + " not exist.");
            return;
        }

        try {
            Method refMethod = request.getMethod();
            //Method targetMethod = target.getClass().getMethod(refMethod.getName(), refMethod.getParameterTypes());
            Object[] pObjects = request.getParameterObject();
            Object resData = refMethod.invoke(target, pObjects);
            response.sendData(resData);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}