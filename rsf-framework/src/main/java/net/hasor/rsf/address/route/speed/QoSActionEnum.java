/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.route.speed;
/**
 * 流控级别，可选的级别有（服务级、方法级、地址级）
 * @version : 2015年4月6日
 * @author 赵永春 (zyc@hasor.net)
 */
public enum QoSActionEnum {
    /**限制接口所有方法的总调用速率。*/
    Service,
    /**限制接口某一个方法的调用速率。*/
    Method,
    /**限制对某一个远程服务机器的调用速率。*/
    Address
}