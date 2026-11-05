/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.directory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.model.fo.ManagementTarget;

public final class ApplicationsRequest extends CommandRequest<ApplicationsResult> {
    @JsonCreator
    public ApplicationsRequest(@JsonProperty("target") ManagementTarget target) {
        super(target);
    }

    @Override
    public CommandType getCommand() {
        return CommandType.APPLICATIONS;
    }
}
