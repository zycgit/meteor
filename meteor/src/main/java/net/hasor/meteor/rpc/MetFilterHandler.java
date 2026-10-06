/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.util.function.Supplier;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;

/**
 * 负责处理 MetFilter 调用
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetFilterHandler implements MetFilterChain {
    private static final Supplier[]            EMPTY_FILTER = new Supplier[0];
    private final        Supplier<MetFilter>[] rsfFilter;
    private final        MetFilterChain        rsfChain;
    private              int                   index;

    public MetFilterHandler(final Supplier<MetFilter>[] rsfFilter, final MetFilterChain rsfChain) {
        this.rsfChain = rsfChain;
        this.index = -1;
        this.rsfFilter = (rsfFilter != null && rsfFilter.length != 0) ? rsfFilter : EMPTY_FILTER;
    }

    public void doFilter(MetRequest request, MetResponse response) throws Throwable {
        this.index++;
        if (this.index < this.rsfFilter.length) {
            this.rsfFilter[this.index].get().doFilter(request, response, this);
        } else {
            this.rsfChain.doFilter(request, response);
        }
    }
}