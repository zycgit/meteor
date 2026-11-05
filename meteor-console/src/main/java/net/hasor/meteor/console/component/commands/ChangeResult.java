/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChangeResult(boolean success, int affected) {
    @JsonCreator
    public ChangeResult(@JsonProperty("success") boolean success, @JsonProperty("affected") int affected) {
        this.success = success;
        this.affected = affected;
    }
}
