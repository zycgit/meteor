/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;

/** 注册中心管理入口；管理凭据统一从 RegistryConfiguration 配置的提供者获取。 */
public interface RegistryAdmin extends AutoCloseable {

    /** 面向集群的治理服务 */
    MetClusterAdmin getClusterAdmin();

    /** 面向实例的治理服务 */
    MetInstanceAdmin getInstanceAdmin(String instanceId);

    /** 面向服务的治理服务 */
    MetServiceAdmin getServiceAdmin(String serviceId);

    /** 面向指定实例中服务的治理服务，实例 ID 不能为空。 */
    MetServiceAdmin getServiceAdmin(String serviceId, String instanceId);
}
