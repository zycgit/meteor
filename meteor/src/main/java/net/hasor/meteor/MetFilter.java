/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
/**
 * 调用请求过滤器
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetFilter {
    /**
     * 执行过滤器
     * @param request rsf请求
     * @param response rsf响应
     * @param chain 过滤器链
     * @throws Throwable 执行期间引发的异常。
     */
    void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable;
}