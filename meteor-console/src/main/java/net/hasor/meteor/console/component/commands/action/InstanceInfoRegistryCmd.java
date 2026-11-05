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
import net.hasor.meteor.console.component.commands.dto.instance.InstanceInfoRequest;
import net.hasor.meteor.console.component.commands.dto.instance.InstanceInfoResult;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetInstanceInfo;

/** 读取实例状态；直连目标的在线状态由实际管理调用确认。 */
public final class InstanceInfoRegistryCmd implements RegistryCommand<InstanceInfoRequest, InstanceInfoResult> {
    @Override
    public InstanceInfoResult execute(CommandContext context, InstanceInfoRequest request) {
        MetInstanceAdmin admin = context.instance(request.getTarget());
        MetInstanceInfo instance = admin.instance();
        return new InstanceInfoResult(instance, instance.isOnline(), admin.isBlock());
    }
}
