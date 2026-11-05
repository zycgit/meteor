/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.metadata;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.component.commands.SuccessResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;

public final class PutMetadataRequest extends CommandRequest<SuccessResult> {
    private final Map<String, Object> values;

    @JsonCreator
    public PutMetadataRequest(@JsonProperty("target") ManagementTarget target, @JsonProperty("values") Map<String, Object> values) {
        super(target);
        this.values = values;
    }

    public Map<String, Object> getValues() {
        return this.values;
    }

    @Override
    public CommandType getCommand() {
        return CommandType.PUT_METADATA;
    }
}
