/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.service;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.registry.admin.MetServiceMember;

public record MembersResult(List<MetServiceMember> items, String nextCursor) {
    @JsonCreator
    public MembersResult(@JsonProperty("items") List<MetServiceMember> items, @JsonProperty("nextCursor") String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }
}
