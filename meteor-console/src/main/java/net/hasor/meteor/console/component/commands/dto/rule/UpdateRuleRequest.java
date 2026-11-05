/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.console.component.commands.ChangeResult;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetRuleType;

public final class UpdateRuleRequest extends CommandRequest<ChangeResult> {
    private final MetRuleType ruleType;
    private final String      rule;

    @JsonCreator
    public UpdateRuleRequest(@JsonProperty("target") ManagementTarget target, @JsonProperty("ruleType") MetRuleType ruleType, @JsonProperty("rule") String rule) {
        super(target);
        this.ruleType = ruleType;
        this.rule = rule;
    }

    public MetRuleType getRuleType() {
        return this.ruleType;
    }

    public String getRule() {
        return this.rule;
    }

    @Override
    public CommandType getCommand() {
        return CommandType.UPDATE_RULE;
    }
}
