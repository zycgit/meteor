/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.annotation;
import java.lang.annotation.*;

/** RPC 方法的协议名称与契约编号。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MetMethod {
    /** 远程方法名；为空时沿用 Java 方法名。 */
    String name() default "";

    /** 数值契约方法编号；0 表示未指定，由协议决定编号范围及用途。 */
    long id() default 0L;
}