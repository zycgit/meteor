/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 实例生命周期与本地服务声明变化，不包含任何注册中心行为。 */
public enum MetContextEvent {
    STARTED,
    SERVICES_CHANGED,
    ONLINE,
    OFFLINE,
    CLOSED
}
