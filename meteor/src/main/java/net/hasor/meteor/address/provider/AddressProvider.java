/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.provider;
import net.hasor.meteor.address.InterAddress;

/**
 *
 * @version : 2015年12月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface AddressProvider {
    /** 是否为分布式调用*/
    boolean isDistributed();

    /** 根据服务ID获取地址*/
    InterAddress get(String serviceID, String methodName, Object[] args);
}