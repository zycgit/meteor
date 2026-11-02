/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import org.springframework.beans.factory.FactoryBean;

final class ServiceBean<T> implements FactoryBean<T> {
    private final Class<T> type;
    private final T        proxy;

    ServiceBean(Class<T> type, MetContext context) {
        this.type = type;
        this.proxy = context.getRsfClient().getRemote(context.getServiceInfo(type));
    }

    @Override
    public T getObject() {
        return this.proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return this.type;
    }
}