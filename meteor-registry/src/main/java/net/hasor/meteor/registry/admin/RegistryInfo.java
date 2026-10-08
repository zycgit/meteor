/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RegistryInfo {
    private String              provider = null;
    private Map<String, String> scope    = new LinkedHashMap<>();

    public String getProvider() {
        return this.provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Map<String, String> getScope() {
        return this.scope;
    }

    public void setScope(Map<String, String> scope) {
        this.scope = scope;
    }
}
