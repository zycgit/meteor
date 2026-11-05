/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.commands.ChangeResult;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.rule.UpdateRuleRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetValidationResult;

/** 更新规则；单元批量操作先校验正文，再依次写入目标实例。 */
public final class UpdateRuleRegistryCmd implements RegistryCommand<UpdateRuleRequest, ChangeResult> {
    @Override
    public ChangeResult execute(CommandContext context, UpdateRuleRequest request) {
        ManagementTarget target = request.getTarget();
        List<String> instances = context.targetInstances(target);
        if (instances != null && !StringUtils.equals("instance", target.getScope())) {
            MetValidationResult validation = context.validateRule(target, request.getRuleType(), request.getRule());
            if (!validation.isValid()) {
                throw new IllegalArgumentException(String.join("; ", validation.getErrors()));
            }
        }
        int affected = context.applyTargets(target, instances, item -> this.write(context, item, request.getRuleType(), request.getRule()));
        return new ChangeResult(true, affected);
    }

    private void write(CommandContext context, ManagementTarget target, MetRuleType type, String rule) {
        switch (context.scope(target)) {
            case "cluster" -> context.cluster().updateRule(type, rule);
            case "instance" -> context.instance(target).updateRule(type, rule);
            case "service" -> context.service(target).updateRule(type, rule);
            default -> throw new IllegalArgumentException("未知治理范围");
        }
    }
}
