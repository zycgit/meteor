/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.filters.thread;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.RsfFilterChain;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.RsfResponse;

/**
 * 负责更新{@link RsfRequestLocal}、{@link RsfResponseLocal}
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public final class LocalWarpFilter implements RsfFilter {
    @Override
    public void doFilter(RsfRequest request, RsfResponse response, RsfFilterChain chain) throws Throwable {
        try {
            RsfRequestLocal.updateLocal(request);
            RsfResponseLocal.updateLocal(response);
            chain.doFilter(request, response);
        } finally {
            RsfRequestLocal.removeLocal();
            RsfResponseLocal.removeLocal();
        }
    }
}