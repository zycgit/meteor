/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.filters.local;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import net.hasor.rsf.*;

/**
 * 优先检查本地是否有服务提供（优先本地服务提供者的调用）。
 * 提示:如果是 p2p 调用则本地调用优先失效。
 * @version : 2014年11月30日
 * @author 赵永春 (zyc@hasor.net)
 */
public class LocalPref implements RsfFilter {
    public void doFilter(RsfRequest request, RsfResponse response, RsfFilterChain chain) throws Throwable {
        if (request.isLocal() && !request.isP2PCalls()) {
            RsfBindInfo<?> bindInfo = request.getBindInfo();
            Supplier<?> provider = request.getContext().getServiceProvider(bindInfo);
            Object target = provider == null ? null : provider.get();
            if (target != null) {
                try {
                    response.sendData(request.getMethod().invoke(target, request.getParameterObject()));
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
