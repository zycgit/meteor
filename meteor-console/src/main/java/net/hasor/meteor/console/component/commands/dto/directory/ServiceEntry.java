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
import net.hasor.meteor.registry.admin.MetServiceInfo;

public record ServiceEntry(MetServiceInfo info, Boolean enabled) {
    @JsonCreator
    public ServiceEntry(@JsonProperty("info") MetServiceInfo info, @JsonProperty("enabled") Boolean enabled) {
        this.info = info;
        this.enabled = enabled;
    }
}
