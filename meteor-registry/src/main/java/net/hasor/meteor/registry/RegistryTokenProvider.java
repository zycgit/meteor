/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;

/** 统一提供当前管理 token，生成、读取和轮换算法由应用实现。 */
@FunctionalInterface
public interface RegistryTokenProvider {
    /** 获取当前 token；允许并发调用，返回 null 或空白时拒绝管理访问，读取异常直接上抛。 */
    String getToken();
}
