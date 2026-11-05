/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.service.ServiceInfoRequest;
import net.hasor.meteor.console.component.commands.dto.service.ServiceInfoResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;

/** 读取服务状态，并在指定实例时附带该实例的接口描述。 */
public final class ServiceInfoRegistryCmd implements RegistryCommand<ServiceInfoRequest, ServiceInfoResult> {
    @Override
    public ServiceInfoResult execute(CommandContext context, ServiceInfoRequest request) {
        ManagementTarget target = request.getTarget();
        MetServiceAdmin admin = context.service(target);
        MetServiceInfo info = admin.info();
        boolean enabled = admin.isEnable();
        MetServiceDescription description = null;
        if (target.isDirect() || StringUtils.isNotBlank(target.getInstanceId())) {
            // 直连实例身份以管理地址实际返回的实例为准。
            String instanceId = target.getInstanceId();
            if (target.isDirect()) {
                instanceId = context.instance(target).instance().getInstanceId();
            }
            description = admin.descriptions().get(instanceId);
        }
        return new ServiceInfoResult(info, enabled, description);
    }
}
