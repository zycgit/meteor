/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 服务与实例的发现关系类型：发布者或订阅者。 */
public enum RelationType {
    PROVIDES,
    CONSUMES
}
