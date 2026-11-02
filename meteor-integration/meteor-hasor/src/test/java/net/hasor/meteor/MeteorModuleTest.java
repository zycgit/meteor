/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeteorModuleTest {
    @Test
    void scansOnlyAnnotatedInterfaces() throws Throwable {
        // 通过模块入口确认注解类和普通接口不会生成服务绑定。
        try (AppContext app = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            binder.bindType(MetConfiguration.class).toInstance(configuration);
        }).addModules(new MeteorModule("net.hasor.meteor")).build()) {
            MetContext context = app.getInstance(MetContext.class);
            assertNotNull(context.getServiceInfo(ScannerTypes.Service.class));
            assertNull(app.getBindInfo(ScannerTypes.AnnotatedClass.class));
            assertNull(app.getBindInfo(ScannerTypes.UnannotatedInterface.class));
        }
    }

    @Test
    void rejectsBlankScanPackage() {
        // 显式传入空包名时，不允许退化为全类路径扫描。
        Hasor builder = Hasor.create().registerShutdownHook(false).addModules(new MeteorModule(""));
        RuntimeException error = assertThrows(RuntimeException.class, builder::build);
        assertTrue(error.toString().contains("Empty Meteor service scan package"));
    }
}
