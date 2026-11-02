/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/** Enables interface scanning; the declaring configuration package is the default. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(MeteorRegistrar.class)
public @interface EnableMeteor {
    String[] basePackages() default {};
}