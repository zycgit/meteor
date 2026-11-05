/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.domain.MetServiceType;

public final class MembersRequest extends CommandRequest<MembersResult> {
    private final MetServiceType role;

    @JsonCreator
    public MembersRequest(@JsonProperty("target") ManagementTarget target, @JsonProperty("role") MetServiceType role) {
        super(target);
        this.role = role;
    }

    public MetServiceType getRole() {
        return this.role;
    }

    @Override
    public CommandType getCommand() {
        return CommandType.MEMBERS;
    }
}
