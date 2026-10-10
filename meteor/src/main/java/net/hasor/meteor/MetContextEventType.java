/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 实例生命周期和本地服务声明的事件类型。 */
public enum MetContextEventType {
    /** 实例启动完成。 */
    STARTED,
    /** 服务登记完成，身份由服务信息区分。 */
    REGISTERED,
    /** 服务已移除。 */
    REMOVED,
    /** 实例上线。 */
    ONLINE,
    /** 实例下线。 */
    OFFLINE,
    /** 实例进入关闭状态。 */
    CLOSED
}
