/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.util.Objects;
import java.util.function.Supplier;
import net.hasor.meteor.MetFilter;

/**
 * 获取服务上配置有效的过滤器。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
class FilterDefine implements Supplier<MetFilter> {
    private final String                        filterID;
    private final Supplier<? extends MetFilter> filterProvider;

    public FilterDefine(String filterID, Supplier<? extends MetFilter> provider) {
        this.filterID = filterID;
        this.filterProvider = Objects.requireNonNull(provider);
    }

    /**过滤器ID*/
    public String filterID() {
        return this.filterID;
    }

    @Override
    public MetFilter get() {
        return this.filterProvider.get();
    }

    public Supplier<? extends MetFilter> getProvider() {
        return this.filterProvider;
    }

    public String toString() {
        return "[" + this.filterID + "]";
    }
}