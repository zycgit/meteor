/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import net.hasor.cobble.concurrent.future.Future;

/** 应用接入注册中心的主动同步接口；生命周期由所属 Meteor 上下文管理。 */
public interface RegistryClient {
    /** 将本机实例、全部服务及发布订阅关系和状态同步到注册中心，Future 在写入完成后结束。 */
    Future<Void> pushSync();

    /** 增量上报指定服务的注册关系、状态和地址快照，不回写实例记录；服务 ID 必填。 */
    Future<Void> pushSync(String serviceId);

    /** 全量读取并应用注册中心配置、刷新地址本并上报结果，即使配置未变也重新应用。 */
    Future<Void> pullSync();

    /** 仅拉取并应用指定本地服务的配置和地址，保留实例配置及其他服务；服务 ID 必填。 */
    Future<Void> pullSync(String serviceId);
}