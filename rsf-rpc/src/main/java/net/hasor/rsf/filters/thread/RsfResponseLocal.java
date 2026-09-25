/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.filters.thread;
import net.hasor.rsf.RsfResponse;
import net.hasor.rsf.domain.warp.AbstractRsfResponseWarp;

/**
 * {@link RsfResponse}接口包装器（当前线程绑定）。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfResponseLocal extends AbstractRsfResponseWarp {
    private static final ThreadLocal<RsfResponse> LOCAL_RESPONSE = new ThreadLocal<RsfResponse>();

    @Override
    protected final RsfResponse getRsfResponse() {
        return LOCAL_RESPONSE.get();
    }

    static void removeLocal() {
        if (LOCAL_RESPONSE.get() != null) {
            LOCAL_RESPONSE.remove();
        }
    }

    static void updateLocal(RsfResponse rsfResponse) {
        removeLocal();
        if (rsfResponse != null) {
            LOCAL_RESPONSE.set(rsfResponse);
        }
    }
}