/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Managed;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.Lifecycle;
import org.noear.solon.core.util.ConditionUtil;

/** Builds the context and registers BeanWraps before injection; starts listeners after Bean initialization. */
final class MeteorContextLifecycle implements Lifecycle {
    private final AppContext    app;
    private final Set<Class<?>> services;
    private       MetContext    context;

    MeteorContextLifecycle(AppContext app, Set<Class<?>> services) {
        this.app = app;
        this.services = services;
    }

    @Override
    public void start() throws Throwable {
        if (this.app.getWrap(MetContext.class) != null) {
            throw new IllegalStateException("Meteor plugin creates MetContext; remove the custom Bean");
        }
        List<MetConfiguration> configurations = this.app.getBeansOfType(MetConfiguration.class);
        if (configurations.size() > 1) {
            throw new IllegalStateException("Only one MetConfiguration Bean is supported");
        }

        MetConfiguration configuration;
        if (configurations.isEmpty()) {
            configuration = SolonSettings.configuration(this.app.cfg(), this.app.getClassLoader());
        } else {
            configuration = configurations.get(0);
        }

        this.app.getBeansOfType(MetExtensionFactory.class).forEach(configuration::addExtension);
        this.context = configuration.buildContext(this.app.getClassLoader());
        try {
            Set<Class<?>> providerTypes = new HashSet<>();
            // Classify every interface before publishing any generated consumer Bean.
            for (Class<?> type : this.services) {
                if (!this.app.getWrapsOfType(type).isEmpty() || declaredProvider(type)) {
                    providerTypes.add(type);
                }
            }
            for (Class<?> type : this.services) {
                bind(type, providerTypes.contains(type));
            }
            this.app.putWrap(MetContext.class, new BeanWrap(this.app, MetContext.class, this.context));
        } catch (Throwable e) {
            try {
                stop();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private boolean declaredProvider(Class<?> type) {
        List<BeanWrap> definitions = new ArrayList<>();
        this.app.beanForeach(wrap -> definitions.add(wrap));
        for (BeanWrap definition : definitions) {
            for (Method method : definition.clz().getDeclaredMethods()) {
                // Factory methods may await a consumer argument; inspect their declared type without invoking them.
                boolean factory = method.isAnnotationPresent(Bean.class);
                factory = factory || method.isAnnotationPresent(Managed.class);
                if (factory && type.isAssignableFrom(method.getReturnType())) {
                    if (ConditionUtil.test(this.app, method)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BeanWrap provider(Class<?> type) {
        List<BeanWrap> providers = this.app.getWrapsOfType(type);
        if (providers.size() != 1) {
            throw new IllegalStateException("Expected one Meteor provider Bean for " + type.getName() + ", found " + providers.size());
        }
        return providers.get(0);
    }

    private <T> void bind(Class<T> type, boolean provider) {
        MetPublisher.ConfigurationBuilder<T> binding;
        if (provider) {
            binding = this.context.publisher().rsfService(type).toProvider(() -> type.cast(provider(type).get()));
        } else {
            binding = this.context.publisher().rsfService(type);
        }

        SolonSettings.configureService(this.app.cfg(), type, binding);
        binding.register();
        if (!provider) {
            T proxy = this.context.getRsfClient().getRemote(this.context.getServiceInfo(type));
            BeanWrap wrap = new BeanWrap(this.app, type, proxy);
            wrap.singletonSet(true);
            this.app.putWrap(type, wrap);
        }
    }

    @Override
    public void postStart() throws Throwable {
        // Solon has completed injection and Bean initialization before postStart.
        try {
            for (Class<?> type : this.services) {
                MetBindInfo<?> service = this.context.getServiceInfo(type);
                if (service.getServiceType() == MetServiceType.Provider) {
                    this.context.getServiceProvider(service).get();
                }
            }
            this.context.start();
        } catch (Throwable e) {
            try {
                stop();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        if (this.context != null) {
            this.context.close();
            this.context = null;
        }
    }
}