/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Arrays;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.loader.CobbleClassScanner;
import net.hasor.meteor.annotation.MetService;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.Props;

/** Solon plugin: publish managed Beans and register missing service interfaces as consumers. */
public final class MeteorPlugin implements Plugin {
    @Override
    public void start(AppContext app) {
        Props properties = app.cfg();
        String scalar = properties.get("meteor.scan-packages");
        String[] packages;
        if (StringUtils.isNotBlank(scalar)) {
            packages = Arrays.stream(scalar.split(",")).map(String::trim) // 单值配置支持逗号分隔的扫描包。
                    .filter(value -> !value.isEmpty()).toArray(String[]::new);
        } else {
            packages = properties.getList("meteor.scan-packages").toArray(String[]::new);
        }

        if (packages.length == 0) {
            if (app.app() == null || app.app().source() == null) {
                throw new IllegalArgumentException("No Solon application source; configure meteor.scan-packages for a standalone AppContext");
            }
            packages = new String[] { app.app().source().getPackageName() };
        }

        for (String name : packages) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("Empty Meteor service scan package");
            }
        }

        CobbleClassScanner scanner = new CobbleClassScanner(app.getClassLoader());
        Set<Class<?>> services = scanner.getClassSet(packages, MetService.class);
        services.removeIf(type -> !type.isInterface() || !type.isAnnotationPresent(MetService.class));
        MeteorContextLifecycle lifecycle = new MeteorContextLifecycle(app, services);
        // Prepare after conditional Bean definitions, before collection/parameter injection review.
        app.lifecycle(-97, lifecycle);
    }

}
