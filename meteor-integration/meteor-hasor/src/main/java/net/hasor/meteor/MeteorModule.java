/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.*;
import net.hasor.core.Module;
import net.hasor.meteor.annotation.MetService;

/** Registers the context and service suppliers using Hasor's native bindings. */
public final class MeteorModule implements Module {
    private final String[] packages;

    public MeteorModule(String... packages) {
        this.packages = packages;
    }

    @Override
    public void loadModule(ApiBinder binder) {
        Settings settings = binder.getSettings();
        String[] packages = this.packages;
        if (packages.length == 0) {
            String[] configured = settings.getStringArray("hasor.loadPackages");
            packages = Arrays.stream(configured).flatMap(value -> {
                return Arrays.stream(value.split(","));
            }).map(value -> {
                String name = value.trim();
                if (name.endsWith(".*")) {
                    return name.substring(0, name.length() - 2);
                }
                return name;
            }).filter(value -> {
                return !value.isEmpty();
            }).toArray(String[]::new);
        }

        for (String name : packages) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("Empty Meteor service scan package");
            }
        }

        Set<Class<?>> services = new HashSet<>();
        if (packages.length > 0) {
            services.addAll(binder.findClass(MetService.class, packages));
        }
        services.removeIf(type -> {
            return !type.isInterface() || !type.isAnnotationPresent(MetService.class);
        });

        Supplier<AppContext> app = binder.getProvider(AppContext.class);
        Set<String> generatedBindings = new HashSet<>();
        MeteorContextSupplier context = new MeteorContextSupplier(app, services, generatedBindings);
        BindInfo<MetContext> binding = binder.bindType(MetContext.class).toProvider(context).asEagerSingleton().toInfo();
        generatedBindings.add(binding.getBindID());
        for (Class<?> service : services) {
            registerService(binder, context, service, generatedBindings);
        }

        HasorUtils.pushStartListener(binder.getEventContext(), (event, data) -> context.start());
        HasorUtils.pushShutdownListener(binder.getEventContext(), (event, data) -> context.close());
    }

    private <T> void registerService(ApiBinder binder, MeteorContextSupplier context, Class<T> type, Set<String> generated) {
        if (binder.getBindInfo(type) == null) {
            ServiceSupplier<T> supplier = new ServiceSupplier<>(type, context);
            BindInfo<T> binding = binder.bindType(type).toProvider(supplier).asEagerSingleton().toInfo();
            generated.add(binding.getBindID());
        }
    }
}
