/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route;
/**
 * 将参数映射为一个Key.
 * @version : 2015年4月16日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ArgsKey {
    String eval(String serviceID, String methodName, Object[] args);
}