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

/** INSTANCE/CONFIGURATION 正文：阻断与请求、响应选项；选项中的 null 表示删除。 */
public final class EntInstanceConfigurationData extends EntData {
    private Boolean             block;
    private Map<String, String> requestOptions  = new LinkedHashMap<>();
    private Map<String, String> responseOptions = new LinkedHashMap<>();

    public Boolean getBlock() {
        return this.block;
    }

    public void setBlock(Boolean block) {
        this.block = block;
    }

    public Map<String, String> getRequestOptions() {
        return this.requestOptions;
    }

    public void setRequestOptions(Map<String, String> values) {
        this.requestOptions = values;
    }

    public Map<String, String> getResponseOptions() {
        return this.responseOptions;
    }

    public void setResponseOptions(Map<String, String> values) {
        this.responseOptions = values;
    }
}
