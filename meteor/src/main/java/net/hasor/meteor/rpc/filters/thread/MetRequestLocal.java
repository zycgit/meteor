/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc.filters.thread;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.domain.warp.AbstractMetRequestWarp;

/**
 * {@link MetRequest}接口包装器（当前线程绑定）。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class MetRequestLocal extends AbstractMetRequestWarp {
    private static final ThreadLocal<MetRequest> LOCAL_REQUEST = new ThreadLocal<>();

    @Override
    protected final MetRequest getRsfRequest() {
        return LOCAL_REQUEST.get();
    }

    /** Bind this call and return the outer call for restoration after nested invocations. */
    static MetRequest updateLocal(MetRequest value) {
        MetRequest previous = LOCAL_REQUEST.get();
        if (value == null) {
            LOCAL_REQUEST.remove();
        } else {
            LOCAL_REQUEST.set(value);
        }
        return previous;
    }
}
