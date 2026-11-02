/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Map;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/** Installs the service scanner, following dbVisitor's scanner registrar pattern. */
public final class MeteorRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition("meteorServiceScanner")) {
            throw new IllegalStateException("Use @EnableMeteor once per Spring container");
        }

        Map<String, Object> attributes = metadata.getAnnotationAttributes(EnableMeteor.class.getName());
        String[] packages = (String[]) attributes.get("basePackages");
        String className = metadata.getClassName();
        String defaultPackage = className.substring(0, className.lastIndexOf('.'));
        BeanDefinitionBuilder scanner = BeanDefinitionBuilder.genericBeanDefinition(ServiceScannerConfigurer.class);
        scanner.addConstructorArgValue(packages);
        scanner.addConstructorArgValue(defaultPackage);
        registry.registerBeanDefinition("meteorServiceScanner", scanner.getBeanDefinition());
    }
}