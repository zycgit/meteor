/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

/** Owns exactly one Meteor context; business Beans remain owned by Spring. */
final class MeteorContextBean implements FactoryBean<MetContext>, BeanFactoryAware, SmartLifecycle, DisposableBean {
    private final    Set<Class<?>>                   services;
    private final    Map<Class<?>, String>           providers;
    private final    Environment                     environment;
    private          ConfigurableListableBeanFactory factory;
    private          MetContext                      context;
    private volatile boolean                         running;
    private          boolean                         started;

    MeteorContextBean(Set<Class<?>> services, Map<Class<?>, String> providers, Environment environment) {
        this.services = services;
        this.providers = providers;
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory factory) {
        this.factory = (ConfigurableListableBeanFactory) factory;
    }

    @Override
    public synchronized MetContext getObject() throws Exception {
        if (this.context != null) {
            return this.context;
        }
        String[] configurations = this.factory.getBeanNamesForType(MetConfiguration.class, true, false);
        if (configurations.length > 1) {
            throw new IllegalStateException("Only one MetConfiguration Bean is supported");
        }

        MetConfiguration configuration;
        if (configurations.length == 0) {
            configuration = SpringSettings.configuration(this.environment, this.factory.getBeanClassLoader());
        } else {
            configuration = this.factory.getBean(configurations[0], MetConfiguration.class);
        }

        for (MetExtensionFactory extension : this.factory.getBeansOfType(MetExtensionFactory.class).values()) {
            configuration.addExtension(extension);
        }

        MetContext created = configuration.buildContext(this.factory.getBeanClassLoader());
        try {
            for (Class<?> type : this.services) {
                bind(created, type);
            }
            this.context = created;
            return created;
        } catch (Throwable e) {
            try {
                created.close();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private <T> void bind(MetContext context, Class<T> type) {
        String name = this.providers.get(type);
        MetPublisher.ConfigurationBuilder<T> binding;
        if (name == null) {
            binding = context.publisher().rsfService(type);
        } else {
            binding = context.publisher().rsfService(type).toProvider(() -> this.factory.getBean(name, type));
        }
        SpringSettings.configureService(this.environment, type, binding);
        binding.register();
    }

    @Override
    public Class<?> getObjectType() {
        return MetContext.class;
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }

        try {
            MetContext context = getObject();
            // Resolve final Spring instances, including post-processors/AOP, before listeners start.
            for (String name : this.providers.values()) {
                this.factory.getBean(name);
            }

            if (this.started) {
                context.online();
            } else {
                context.start();
                this.started = true;
            }
            this.running = true;
        } catch (Exception | Error e) {
            try {
                destroy();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Cannot start Meteor", e);
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public synchronized void stop() {
        if (this.context != null && this.running) {
            this.context.offline();
            this.running = false;
        }
    }

    @Override
    public synchronized void destroy() throws Exception {
        if (this.context != null) {
            this.context.close();
            this.context = null;
            this.running = false;
        }
    }
}