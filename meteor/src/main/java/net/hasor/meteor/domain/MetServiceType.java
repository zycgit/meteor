/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
/**
 * 服务类型，用于区分注册的服务是提供者还是消费者。
 * @version : 2016年3月10日
 * @author 赵永春 (zyc@hasor.net)
 */
public enum MetServiceType {
    /**提供者*/
    Provider,//
    /**消费者*/
    Consumer
}