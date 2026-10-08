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

/** 实例下 SERVICE/CONFIGURATION 正文，也是服务目录的治理字段：启停覆盖值与元信息。 */
public class EntServiceConfigurationData extends EntData {
    private Boolean             enabled  = null;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public Boolean getEnabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
    }

    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public void setMetadata(Map<String, Object> value) {
        this.metadata = value;
    }
}
