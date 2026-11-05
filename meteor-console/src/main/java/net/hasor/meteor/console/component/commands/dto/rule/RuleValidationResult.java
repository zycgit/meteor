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

public record RuleValidationResult(boolean valid, String[] errors) {
    @JsonCreator
    public RuleValidationResult(@JsonProperty("valid") boolean valid, @JsonProperty("errors") String[] errors) {
        this.valid = valid;
        this.errors = errors;
    }
}
