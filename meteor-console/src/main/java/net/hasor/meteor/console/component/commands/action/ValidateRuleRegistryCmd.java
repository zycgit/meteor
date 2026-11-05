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
import net.hasor.meteor.console.component.commands.dto.rule.RuleValidationResult;
import net.hasor.meteor.console.component.commands.dto.rule.ValidateRuleRequest;
import net.hasor.meteor.registry.admin.MetValidationResult;

/** 在目标治理范围校验规则，不保存正文。 */
public final class ValidateRuleRegistryCmd implements RegistryCommand<ValidateRuleRequest, RuleValidationResult> {
    @Override
    public RuleValidationResult execute(CommandContext context, ValidateRuleRequest request) {
        MetValidationResult validation = context.validateRule(request.getTarget(), request.getRuleType(), request.getRule());
        return new RuleValidationResult(validation.isValid(), validation.getErrors());
    }
}
