/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 实体业务类型；规则必须归属于全局、实例或服务。 */
public enum EntityType {
    GLOBAL,
    INSTANCE,
    SERVICE,
    RULE
}
