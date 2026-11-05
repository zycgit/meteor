/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.instance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.registry.admin.MetInstanceInfo;

public record InstanceInfoResult(MetInstanceInfo instance, boolean online, boolean blocked) {
    @JsonCreator
    public InstanceInfoResult(@JsonProperty("instance") MetInstanceInfo instance, @JsonProperty("online") boolean online, @JsonProperty("blocked") boolean blocked) {
        this.instance = instance;
        this.online = online;
        this.blocked = blocked;
    }
}
