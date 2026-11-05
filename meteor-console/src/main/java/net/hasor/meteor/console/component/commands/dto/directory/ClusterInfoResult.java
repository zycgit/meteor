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
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.admin.RegistryStatus;

public record ClusterInfoResult(RegistryInfo info, RegistryStatus status) {
    @JsonCreator
    public ClusterInfoResult(@JsonProperty("info") RegistryInfo info, @JsonProperty("status") RegistryStatus status) {
        this.info = info;
        this.status = status;
    }
}
