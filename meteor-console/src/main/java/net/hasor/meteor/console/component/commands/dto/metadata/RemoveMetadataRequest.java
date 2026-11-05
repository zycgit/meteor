/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.component.commands.SuccessResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;

public final class RemoveMetadataRequest extends CommandRequest<SuccessResult> {
    private final String[] keys;

    @JsonCreator
    public RemoveMetadataRequest(@JsonProperty("target") ManagementTarget target, @JsonProperty("keys") String[] keys) {
        super(target);
        this.keys = keys;
    }

    public String[] getKeys() {
        return this.keys;
    }

    @Override
    public CommandType getCommand() {
        return CommandType.REMOVE_METADATA;
    }
}
