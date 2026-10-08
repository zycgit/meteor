/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 记录存续方式：持久保存或跟随注册会话存续。 */
public enum RecordLifecycle {
    PERSISTENT,
    EPHEMERAL
}
