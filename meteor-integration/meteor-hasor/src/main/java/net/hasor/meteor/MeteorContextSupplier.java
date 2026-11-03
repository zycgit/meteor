/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.*;
import java.util.function.Supplier;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.core.AppContext;
import net.hasor.core.BindInfo;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;

/** Owns the context supplied to Hasor; service implementations remain container-owned. */
final class MeteorContextSupplier implements Supplier<MetContext>, AutoCloseable {
    private final Supplier<AppContext> app;
    private final Set<Class<?>>        services;
    private final Set<String>          generatedBindings;
    private       MetContext           context;

    MeteorContextSupplier(Supplier<AppContext> app, Set<Class<?>> services, Set<String> generatedBindings) {
        this.app = app;
        this.services = services;
        this.generatedBindings = generatedBindings;
    }

    @Override
    public synchronized MetContext get() {
        if (this.context != null) {
            return this.context;
        }

        AppContext app = this.app.get();
        // Resolve definitions after every Module has loaded; never treat our suppliers as providers.
        BindInfo<?> configurationBinding = null;
        Map<Class<?>, BindInfo<?>> providers = new HashMap<>();
        List<MetExtensionFactory> extensions = new ArrayList<>();
        for (String id : app.getBindIDs()) {
            if (this.generatedBindings.contains(id)) {
                continue;
            }

            BindInfo<?> binding = app.getBindInfo(id);
            Class<?> beanType = binding.getBindType();
            if (MetContext.class.isAssignableFrom(beanType)) {
                throw new IllegalStateException("MeteorModule creates MetContext; remove the custom Bean");
            }

            for (Class<?> service : this.services) {
                if (service.isAssignableFrom(beanType) && providers.putIfAbsent(service, binding) != null) {
                    throw new IllegalStateException("Multiple Meteor provider Beans for " + service.getName());
                }
            }

            if (MetConfiguration.class.isAssignableFrom(beanType)) {
                if (configurationBinding != null) {
                    throw new IllegalStateException("Only one MetConfiguration Bean is supported");
                }
                configurationBinding = binding;
            }

            if (MetExtensionFactory.class.isAssignableFrom(beanType)) {
                extensions.add((MetExtensionFactory) app.getInstance(binding));
            }
        }

        MetConfiguration configuration;
        if (configurationBinding == null) {
            configuration = MeteorSettings.configuration(app.getSettings(), app.getClassLoader());
            MetExtensionFactory registry = MeteorSettings.registry(app.getSettings(), app.getClassLoader(), //
                    extensions, (name, type) -> {
                        if (StringUtils.isNotBlank(name)) {
                            return app.findBindingBean(name, type);
                        }
                        List<? extends BindInfo<?>> bindings = app.findBindingRegister(type);
                        if (bindings.size() != 1) {
                            String message = "meteor.registry requires one Bean of " + type.getName();
                            throw new IllegalArgumentException(message + "; specify its Bean name");
                        }
                        return app.getInstance(bindings.get(0));
                    });
            if (registry != null) {
                configuration.addExtension(registry);
            }
        } else {
            configuration = (MetConfiguration) app.getInstance(configurationBinding);
        }
        extensions.forEach(configuration::addExtension);

        try {
            MetContext context = configuration.buildContext(app.getClassLoader());
            try {
                for (Class<?> type : this.services) {
                    publish(context, type, providers.get(type), app);
                }
                this.context = context;
                return context;
            } catch (Throwable e) {
                try {
                    context.close();
                } catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        } catch (Exception e) {
            throw ExceptionUtils.toRuntime(e);
        }
    }

    private <T> void publish(MetContext context, Class<T> type, BindInfo<?> definition, AppContext app) {
        MetPublisher.ConfigurationBuilder<T> binding;
        if (definition == null) {
            binding = context.publisher().rsfService(type);
        } else {
            binding = context.publisher().rsfService(type).toProvider(() -> {
                return type.cast(app.getInstance(definition));
            });
        }

        binding.register();
    }

    void start() {
        try {
            MetContext context = get();
            for (Class<?> type : this.services) {
                MetBindInfo<?> service = context.getServiceInfo(type);
                if (service.getServiceType() == MetServiceType.Provider) {
                    context.getServiceProvider(service).get();
                }
            }
            context.start();
        } catch (Exception | Error e) {
            try {
                close();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    @Override
    public void close() throws Exception {
        if (this.context != null) {
            this.context.close();
        }
    }
}