/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import net.hasor.meteor.MetExtension;

/** 将原生注册服务的资源交给所属 Meteor 上下文管理。 */
final class DubboRegistryExtension implements MetExtension {
    private final DubboRegistryService service;

    DubboRegistryExtension(DubboRegistryService service) {
        this.service = service;
    }

    @Override
    public <T> T getService(Class<T> type) {
        return type.isInstance(this.service) ? type.cast(this.service) : null;
    }

    @Override
    public void beforeStart() {
        this.service.open();
    }

    @Override
    public void afterStart() {
        this.service.start();
    }

    @Override
    public void close() {
        this.service.close();
    }
}
