/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;

/** 复用所属上下文的 RPC 能力，按地址直接管理目标应用，不访问注册中心。 */
public interface TargetAdmin {
    /** 创建目标实例的直连管理代理。 */
    MetInstanceAdmin getInstanceAdminByAddress(InterAddress address);

    /** 创建目标实例中指定服务的直连管理代理。 */
    MetServiceAdmin getServiceAdminByAddress(InterAddress address, String serviceId);
}
