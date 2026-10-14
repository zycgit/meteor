/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 指定治理范围的开关覆盖；服务启停与实例阻断使用各自字段。 */
public class EntConfigurationData {
    private Boolean enabled;
    private Boolean block;

    public Boolean getEnabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getBlock() {
        return this.block;
    }

    public void setBlock(Boolean block) {
        this.block = block;
    }
}
