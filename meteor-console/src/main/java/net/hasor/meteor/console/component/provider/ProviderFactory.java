/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.registry.RegistryTokenProvider;

/** 按具体接入方式创建连接，返回的独占连接由调用方关闭。 */
public interface ProviderFactory {
    /** 注册中心接入类型，与连接配置中的 type 对应。 */
    String name();

    /** credentials 用于本地存储管理；Remote 使用连接配置中的 managementToken。 */
    ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception;
}
