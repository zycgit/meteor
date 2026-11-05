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
import net.hasor.meteor.console.component.commands.dto.rule.ResetRuleRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetRuleType;

/** 清除指定范围或目标单元内的规则。 */
public final class ResetRuleRegistryCmd implements RegistryCommand<ResetRuleRequest, ChangeResult> {
    @Override
    public ChangeResult execute(CommandContext context, ResetRuleRequest request) {
        ManagementTarget target = request.getTarget();
        List<String> instances = context.targetInstances(target);
        int affected = context.applyTargets(target, instances, item -> this.reset(context, item, request.getRuleType()));
        return new ChangeResult(true, affected);
    }

    private void reset(CommandContext context, ManagementTarget target, MetRuleType type) {
        switch (context.scope(target)) {
            case "cluster" -> context.cluster().resetRule(type);
            case "instance" -> context.instance(target).resetRule(type);
            case "service" -> context.service(target).resetRule(type);
            default -> throw new IllegalArgumentException("未知治理范围");
        }
    }
}
