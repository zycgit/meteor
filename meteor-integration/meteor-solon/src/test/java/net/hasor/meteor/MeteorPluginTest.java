/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;
import static org.junit.jupiter.api.Assertions.*;

class MeteorPluginTest {
    @Test
    void scansOnlyAnnotatedInterfacesFromNativePackageList() throws Throwable {
        // 原生索引列表经插件扫描，只注册注解接口。
        Props properties = new Props();
        properties.setProperty("meteor.scan-packages[0]", "net.hasor.meteor");
        properties.setProperty("meteor.scan-packages[1]", "net.hasor.example.empty");
        properties.setProperty("meteor.connectors.rsf.host", "127.0.0.1");
        properties.setProperty("meteor.connectors.rsf.port", "0");
        AppContext app = new AppContext(null, getClass().getClassLoader(), properties);
        try {
            new MeteorPlugin().start(app);
            app.start();
            MetContext context = app.getBean(MetContext.class);
            assertNotNull(context.getServiceInfo(ScannerTypes.Service.class));
            assertNull(app.getWrap(ScannerTypes.AnnotatedClass.class));
            assertNull(app.getWrap(ScannerTypes.UnannotatedInterface.class));
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsBlankScanPackage() {
        // 索引列表中的空包名不能退化成全类路径扫描。
        Props properties = new Props();
        properties.setProperty("meteor.scan-packages[0]", " ");
        AppContext app = new AppContext(null, getClass().getClassLoader(), properties);
        assertThrows(IllegalArgumentException.class, () -> new MeteorPlugin().start(app));
    }
}
