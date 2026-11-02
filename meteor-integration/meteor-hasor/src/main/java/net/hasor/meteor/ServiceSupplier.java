/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.function.Supplier;
import net.hasor.meteor.domain.MetServiceType;

/** Supplies the container implementation, or a MetClient consumer when no implementation exists. */
final class ServiceSupplier<T> implements Supplier<T> {
    private final Class<T>             serviceType;
    private final Supplier<MetContext> context;

    ServiceSupplier(Class<T> serviceType, Supplier<MetContext> context) {
        this.serviceType = serviceType;
        this.context = context;
    }

    @Override
    public T get() {
        MetContext context = this.context.get();
        MetBindInfo<T> service = context.getServiceInfo(this.serviceType);
        if (service.getServiceType() == MetServiceType.Provider) {
            return context.getServiceProvider(service).get();
        }

        return context.getRsfClient().getRemote(service);
    }
}
