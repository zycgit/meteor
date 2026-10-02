/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.filters.thread;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.domain.warp.AbstractRsfRequestWarp;

/**
 * {@link RsfRequest}接口包装器（当前线程绑定）。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfRequestLocal extends AbstractRsfRequestWarp {
    private static final ThreadLocal<RsfRequest> LOCAL_REQUEST = new ThreadLocal<>();

    @Override
    protected final RsfRequest getRsfRequest() {
        return LOCAL_REQUEST.get();
    }

    /** Bind this call and return the outer call for restoration after nested invocations. */
    static RsfRequest updateLocal(RsfRequest value) {
        RsfRequest previous = LOCAL_REQUEST.get();
        if (value == null) {
            LOCAL_REQUEST.remove();
        } else {
            LOCAL_REQUEST.set(value);
        }
        return previous;
    }
}
