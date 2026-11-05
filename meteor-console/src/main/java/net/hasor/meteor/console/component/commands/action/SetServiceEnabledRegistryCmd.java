/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import java.util.List;
import net.hasor.meteor.console.component.commands.ChangeResult;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.service.SetServiceEnabledRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetServiceAdmin;

/** 切换服务可用状态，支持按目标单元批量执行。 */
public final class SetServiceEnabledRegistryCmd implements RegistryCommand<SetServiceEnabledRequest, ChangeResult> {
    @Override
    public ChangeResult execute(CommandContext context, SetServiceEnabledRequest request) {
        ManagementTarget target = request.getTarget();
        List<String> instances = context.targetInstances(target);
        int affected = context.applyTargets(target, instances, item -> this.setEnabled(context, item, request.isEnabled()));
        return new ChangeResult(true, affected);
    }

    private void setEnabled(CommandContext context, ManagementTarget target, boolean enabled) {
        MetServiceAdmin admin = context.service(target);
        if (enabled) {
            admin.enable();
        } else {
            admin.disable();
        }
    }
}
