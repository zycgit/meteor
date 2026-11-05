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
import net.hasor.meteor.console.component.commands.dto.rule.SetRuleEnabledRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetRuleType;

/** 切换注册中心保存的规则状态，保留其正文。 */
public final class SetRuleEnabledRegistryCmd implements RegistryCommand<SetRuleEnabledRequest, ChangeResult> {
    @Override
    public ChangeResult execute(CommandContext context, SetRuleEnabledRequest request) {
        ManagementTarget target = request.getTarget();
        List<String> instances = context.targetInstances(target);
        int affected = context.applyTargets(target, instances, item -> this.setEnabled(context, item, request.getRuleType(), request.isEnabled()));
        return new ChangeResult(true, affected);
    }

    private void setEnabled(CommandContext context, ManagementTarget target, MetRuleType type, boolean enabled) {
        if (target.isDirect()) {
            throw new UnsupportedOperationException("规则启停需要注册中心保留正文，直连请保存或清除规则");
        }
        switch (context.scope(target)) {
            case "cluster" -> {
                if (enabled) {
                    context.cluster().enableRule(type);
                } else {
                    context.cluster().disableRule(type);
                }
            }
            case "instance" -> {
                if (enabled) {
                    context.instance(target).enableRule(type);
                } else {
                    context.instance(target).disableRule(type);
                }
            }
            case "service" -> {
                if (enabled) {
                    context.service(target).enableRule(type);
                } else {
                    context.service(target).disableRule(type);
                }
            }
            default -> throw new IllegalArgumentException("未知治理范围");
        }
    }
}
