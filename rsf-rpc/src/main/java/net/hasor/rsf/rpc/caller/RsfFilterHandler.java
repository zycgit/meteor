/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.RsfFilterChain;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.RsfResponse;

import java.util.function.Supplier;

/**
 * 负责处理 RsfFilter 调用
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfFilterHandler implements RsfFilterChain {
    private static final Supplier[]            EMPTY_FILTER = new Supplier[0];
    private final        Supplier<RsfFilter>[] rsfFilter;
    private final        RsfFilterChain        rsfChain;
    private              int                   index;

    public RsfFilterHandler(final Supplier<RsfFilter>[] rsfFilter, final RsfFilterChain rsfChain) {
        this.rsfChain = rsfChain;
        this.index = -1;
        this.rsfFilter = (rsfFilter != null && rsfFilter.length != 0) ? rsfFilter : EMPTY_FILTER;
    }

    public void doFilter(RsfRequest request, RsfResponse response) throws Throwable {
        this.index++;
        if (this.index < this.rsfFilter.length) {
            this.rsfFilter[this.index].get().doFilter(request, response, this);
        } else {
            this.rsfChain.doFilter(request, response);
        }
    }
}