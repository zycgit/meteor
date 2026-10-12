/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 根 SERVICE/CONFIGURATION 正文：持久服务目录及治理配置；服务 ID 由 EntityRef 提供。 */
public final class EntServiceCatalogData extends EntServiceConfigurationData {
    private String type    = null; // 服务接口类型
    private String group   = null;
    private String name    = null;
    private String version = null;

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getGroup() {
        return this.group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
