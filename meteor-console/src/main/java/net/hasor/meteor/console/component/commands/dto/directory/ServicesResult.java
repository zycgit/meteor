/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.dto.directory;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ServicesResult(List<ServiceEntry> items, String nextCursor) {
    @JsonCreator
    public ServicesResult(@JsonProperty("items") List<ServiceEntry> items, @JsonProperty("nextCursor") String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }
}
