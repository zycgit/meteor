/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.directory.InstancesRequest;
import net.hasor.meteor.console.component.commands.dto.directory.InstancesResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetPage;

/** 分页查询当前注册中心或指定应用的实例目录。 */
public final class InstancesRegistryCmd implements RegistryCommand<InstancesRequest, InstancesResult> {
    @Override
    public InstancesResult execute(CommandContext context, InstancesRequest request) {
        ManagementTarget target = request.getTarget();
        if (target.isDirect()) {
            throw new UnsupportedOperationException("直连模式只查询指定目标实例");
        }
        MetPage<MetInstanceInfo> page = context.cluster().instances(target.getApplicationName(), target.directoryQuery());
        return new InstancesResult(page.getItems(), page.getNextCursor());
    }
}
