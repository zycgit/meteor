/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class ServiceScannerConfigurerTest {
    @EnableMeteor
    @Configuration(proxyBeanMethods = false)
    static class Enable {
    }

    @Test
    void usesEnablingConfigurationPackageWhenMeteorPackagesAreAbsent() {
        // 不指定 basePackages 或 meteor.scan-packages，使用配置类所在包。
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext()) {
            Map<String, Object> properties = Map.of("meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", "0");
            app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("defaults", properties));
            app.register(Enable.class);
            app.refresh();
            MetContext context = app.getBean(MetContext.class);
            assertNotNull(context.getServiceInfo(ScannerTypes.Service.class));
            assertNotNull(app.getBean(ScannerTypes.Service.class));
        }
    }

    @Test
    void scansOnlyAnnotatedInterfacesFromNativePackageList() {
        // 通过原生 Environment 索引列表和 @EnableMeteor 完成扫描、注册。
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext()) {
            Map<String, Object> properties = Map.of("meteor.scan-packages[0]", "net.hasor.meteor", "meteor.scan-packages[1]", "net.hasor.example.empty", "meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", "0");
            app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scan", properties));
            app.register(Enable.class);
            app.refresh();
            MetContext context = app.getBean(MetContext.class);
            assertNotNull(context.getServiceInfo(ScannerTypes.Service.class));
            assertEquals(0, app.getBeanNamesForType(ScannerTypes.AnnotatedClass.class).length);
            assertEquals(0, app.getBeanNamesForType(ScannerTypes.UnannotatedInterface.class).length);
        }
    }

    @Test
    void rejectsBlankScanPackage() {
        // 显式空包名必须在扫描前拒绝。
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext()) {
            app.registerBean(ServiceScannerConfigurer.class, () -> new ServiceScannerConfigurer(new String[] { "" }, "net.hasor.example"));
            assertThrows(IllegalArgumentException.class, app::refresh);
        }
    }
}
