/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.annotation;
import java.lang.annotation.*;

/** 枚举常量的协议名称与数值。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MetEnumValue {
    /** 枚举符号或 XML 文本；为空时沿用 Java 或序列化映射名称。 */
    String name() default "";

    /** 枚举数值；0 是实际数值，文本协议忽略它。 */
    int id() default 0;
}