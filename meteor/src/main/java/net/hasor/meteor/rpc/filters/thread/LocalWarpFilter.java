/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc.filters.thread;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;

/**
 * 负责更新{@link MetRequestLocal}、{@link MetResponseLocal}
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public final class LocalWarpFilter implements MetFilter {
    @Override
    public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
        MetRequest previousRequest = MetRequestLocal.updateLocal(request);
        MetResponse previousResponse = MetResponseLocal.updateLocal(response);
        try {
            chain.doFilter(request, response);
        } finally {
            MetRequestLocal.updateLocal(previousRequest);
            MetResponseLocal.updateLocal(previousResponse);
        }
    }
}
