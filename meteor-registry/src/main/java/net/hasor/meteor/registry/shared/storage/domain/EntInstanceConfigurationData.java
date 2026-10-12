/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** INSTANCE/CONFIGURATION 正文：实例的业务阻断治理。 */
public final class EntInstanceConfigurationData extends EntData {
    private Boolean block;

    public Boolean getBlock() {
        return this.block;
    }

    public void setBlock(Boolean block) {
        this.block = block;
    }
}
