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
import net.hasor.meteor.console.component.commands.dto.rule.ReadRuleRequest;
import net.hasor.meteor.console.component.commands.dto.rule.RuleResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;

/** 读取指定治理范围的规则及其可操作能力。 */
public final class ReadRuleRegistryCmd implements RegistryCommand<ReadRuleRequest, RuleResult> {
    @Override
    public RuleResult execute(CommandContext context, ReadRuleRequest request) {
        ManagementTarget target = request.getTarget();
        MetRuleType type = request.getRuleType();
        String scope = context.scope(target);
        String content;
        Boolean enabled;
        switch (scope) {
            case "cluster" -> {
                MetClusterAdmin admin = context.cluster();
                content = admin.fetchRule(type);
                enabled = admin.isRuleEnabled(type);
            }
            case "instance" -> {
                MetInstanceAdmin admin = context.instance(target);
                content = admin.fetchRule(type);
                enabled = null;
                if (!target.isDirect()) {
                    enabled = admin.isRuleEnabled(type);
                }
            }
            case "service" -> {
                MetServiceAdmin admin = context.service(target);
                content = admin.fetchRule(type);
                enabled = admin.isRule(type);
            }
            default -> throw new IllegalArgumentException("未知治理范围：" + scope);
        }
        return new RuleResult(type, content, enabled, !target.isDirect(), !StringUtils.equals("instance", scope));
    }
}
