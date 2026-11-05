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
import net.hasor.meteor.registry.admin.MetRuleType;

public record RuleResult(MetRuleType type, String rule, Boolean enabled, boolean switchSupported, boolean validationSupported) {
    @JsonCreator
    public RuleResult(@JsonProperty("type") MetRuleType type, @JsonProperty("rule") String rule, @JsonProperty("enabled") Boolean enabled, @JsonProperty("switchSupported") boolean switchSupported, @JsonProperty("validationSupported") boolean validationSupported) {
        this.type = type;
        this.rule = rule;
        this.enabled = enabled;
        this.switchSupported = switchSupported;
        this.validationSupported = validationSupported;
    }
}
