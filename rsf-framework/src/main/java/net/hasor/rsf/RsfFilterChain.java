/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
/**
 * 负责执行过滤器链后面的过滤器。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RsfFilterChain {
    /**
     * 执行过滤器
     * @param request rsf请求
     * @param response rsf响应
     * @throws Throwable 执行期间引发的异常。
     */
    void doFilter(RsfRequest request, RsfResponse response) throws Throwable;
}