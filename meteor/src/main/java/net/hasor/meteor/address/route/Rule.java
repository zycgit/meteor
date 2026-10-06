/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route;
/**
 * 路由规则
 * @version : 2015年3月29日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Rule {
    /**路由规则ID*/
    String routeID();

    /**路由规则原文*/
    String rawRoute();

    /**规则是否启用*/
    boolean enable();
}