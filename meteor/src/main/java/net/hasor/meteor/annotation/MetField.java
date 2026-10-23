/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.annotation;
import java.lang.annotation.*;

/** DTO 属性的协议名称与稳定编号。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface MetField {
    /** 字段名；为空时沿用 Java 或序列化映射名称。 */
    String name() default "";

    /** 字段编号；0 表示未指定。生成 XDR 时按正数且唯一的编号升序排列，允许间隔；文件模式沿用契约顺序。 */
    int id() default 0;
}