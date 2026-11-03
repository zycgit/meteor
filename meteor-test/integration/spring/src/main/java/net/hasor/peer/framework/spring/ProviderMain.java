/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework.spring;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.example.integration.FrameworkOrderServiceImpl;
import net.hasor.meteor.EnableMeteor;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.peer.framework.FrameworkPeerUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;

/** Spring provider，参数为配置文件和结果文件。 */
@EnableMeteor(basePackages = FrameworkPeerUtils.SERVICE_PACKAGE)
@Configuration(proxyBeanMethods = false)
public class ProviderMain {
    public static void main(String[] args) throws Exception {
        Properties settings = FrameworkPeerUtils.settings(args[0]);
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext()) {
            app.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("scenario", settings));
            if (StringUtils.equals(settings.getProperty("test.backend"), "jdbc")) {
                app.registerBean("registryDb", DataSource.class, () -> FrameworkPeerUtils.database(settings));
            }

            if (StringUtils.equals(settings.getProperty("test.backend"), "dubbo")) {
                app.registerBean("nativeRegistry", MetExtensionFactory.class, () -> {
                    return FrameworkPeerUtils.dubbo(settings);
                });
            }

            app.register(ProviderMain.class);
            app.refresh();
            app.registerShutdownHook();

            MetContext meteor = app.getBean(MetContext.class);
            FrameworkPeerUtils.ready(meteor, Path.of(args[1]));
            new CountDownLatch(1).await();
        }
    }

    @Bean
    public FrameworkOrderServiceImpl orders() {
        return new FrameworkOrderServiceImpl();
    }
}
