/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 实例生命周期与本地服务声明变化，不包含任何注册中心行为。 */
public final class MetContextEvent {
    private final MetContextEventType type;
    private final MetBindInfo<?>      serviceInfo;

    public MetContextEvent(MetContextEventType type, MetBindInfo<?> serviceInfo) {
        this.type = type;
        this.serviceInfo = serviceInfo;
    }

    /** 已经发生的生命周期或服务变化。 */
    public MetContextEventType getType() {
        return this.type;
    }

    /** 服务登记、移除事件对应的服务 ID；实例生命周期事件为 null。 */
    public String getServiceId() {
        return this.serviceInfo == null ? null : this.serviceInfo.getBindID();
    }

    /** 对应的服务声明，可通过 getServiceType() 判断身份；实例生命周期事件为 null。 */
    public MetBindInfo<?> getServiceInfo() {
        return this.serviceInfo;
    }
}
