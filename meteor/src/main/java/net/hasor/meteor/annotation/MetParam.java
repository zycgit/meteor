/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.annotation;
import java.lang.annotation.*;

/** RPC 参数的协议名称与稳定编号。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface MetParam {
    /** 参数名；为空时读取 Java 参数名。 */
    String name() default "";

    /** 参数编号；0 表示未指定。 */
    int id() default 0;
}