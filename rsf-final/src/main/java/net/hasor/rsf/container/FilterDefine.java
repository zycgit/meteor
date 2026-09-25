/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.container;
import net.hasor.rsf.RsfFilter;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 获取服务上配置有效的过滤器。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
class FilterDefine implements Supplier<RsfFilter> {
    private String                        filterID;
    private Supplier<? extends RsfFilter> filterProvider;

    //
    public FilterDefine(String filterID, Supplier<? extends RsfFilter> provider) {
        this.filterID = filterID;
        this.filterProvider = Objects.requireNonNull(provider);
    }
    //

    /**过滤器ID*/
    public String filterID() {
        return this.filterID;
    }

    @Override
    public RsfFilter get() {
        return this.filterProvider.get();
    }

    public Supplier<? extends RsfFilter> getProvider() {
        return this.filterProvider;
    }

    public String toString() {
        return "[" + this.filterID + "]";
    }
}