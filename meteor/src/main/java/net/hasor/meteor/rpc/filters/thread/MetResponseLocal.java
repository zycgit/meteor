/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc.filters.thread;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.warp.AbstractMetResponseWarp;

/**
 * {@link MetResponse}接口包装器（当前线程绑定）。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class MetResponseLocal extends AbstractMetResponseWarp {
    private static final ThreadLocal<MetResponse> LOCAL_RESPONSE = new ThreadLocal<>();

    @Override
    protected final MetResponse getRsfResponse() {
        return LOCAL_RESPONSE.get();
    }

    /** Bind this call and return the outer call for restoration after nested invocations. */
    static MetResponse updateLocal(MetResponse value) {
        MetResponse previous = LOCAL_RESPONSE.get();
        if (value == null) {
            LOCAL_RESPONSE.remove();
        } else {
            LOCAL_RESPONSE.set(value);
        }
        return previous;
    }
}
