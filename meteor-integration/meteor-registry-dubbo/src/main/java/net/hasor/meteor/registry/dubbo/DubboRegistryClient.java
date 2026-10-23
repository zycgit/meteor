/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import net.hasor.meteor.MetBindInfo;

/** 原生 Dubbo 注册发现入口；业务代理始终由所属 Meteor 上下文创建。 */
public interface DubboRegistryClient {
    /** 引用原生未分组、未版本化的服务，使用独立消费者绑定，不改变 Meteor 默认配置。 */
    <T> T reference(Class<T> serviceType);

    /** 引用已经登记的消费者，使用其服务别名、分组、版本和调用配置。 */
    <T> T reference(MetBindInfo<T> service);

    /** 注册中心当前是否可用，不代表目标服务已有提供者。 */
    boolean isAvailable();
}
