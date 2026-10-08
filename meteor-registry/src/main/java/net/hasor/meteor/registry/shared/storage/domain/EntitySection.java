/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 同一业务实体的数据片段：持久配置与会话运行信息分别读写。 */
public enum EntitySection {
    CONFIGURATION,
    RUNTIME
}
