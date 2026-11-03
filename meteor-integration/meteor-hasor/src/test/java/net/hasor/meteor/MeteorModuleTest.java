/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeteorModuleTest {
    @Test
    void scansOnlyAnnotatedInterfaces() throws Throwable {
        // 通过模块入口确认注解类和普通接口不会生成服务绑定。
        try (AppContext app = Hasor.create().registerShutdownHook(false) //
                .addSettings(Settings.DefaultNameSpace, "hasor.loadPackages", "net.hasor.meteor") //
                .addModules(binder -> {
                    MetConfiguration configuration = new MetConfiguration();
                    configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                    binder.bindType(MetConfiguration.class).toInstance(configuration);
                }).addModules(new MeteorModule()).build()) {
            MetContext context = app.getInstance(MetContext.class);
            assertNotNull(context.getServiceInfo(ScannerTypes.Service.class));
            assertNull(app.getBindInfo(ScannerTypes.AnnotatedClass.class));
            assertNull(app.getBindInfo(ScannerTypes.UnannotatedInterface.class));
        }
    }

    @Test
    void blankScanScopeDoesNotScanClasspath() throws Throwable {
        // 原生扫描范围为空时，不扫描任何接口，不退化为扫描整个类路径。
        try (AppContext app = Hasor.create().registerShutdownHook(false) //
                .addSettings(Settings.DefaultNameSpace, "hasor.loadPackages", " , ") //
                .addModules(binder -> {
                    MetConfiguration configuration = new MetConfiguration();
                    configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                    binder.bindType(MetConfiguration.class).toInstance(configuration);
                }).addModules(new MeteorModule()).build()) {
            MetContext context = app.getInstance(MetContext.class);
            assertTrue(context.getServiceIDs().isEmpty());
            assertNull(app.getBindInfo(ScannerTypes.Service.class));
        }
    }
}
