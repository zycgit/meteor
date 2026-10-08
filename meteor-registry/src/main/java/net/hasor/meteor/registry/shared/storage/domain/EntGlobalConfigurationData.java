/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.LinkedHashMap;
import java.util.Map;

/** GLOBAL/CONFIGURATION 正文：全局默认元信息，不包含服务启停状态。 */
public final class EntGlobalConfigurationData extends EntData {
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
