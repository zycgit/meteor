/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.annotation;
import java.lang.annotation.*;

/** RPC 单个命名返回值的协议名称。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MetReturn {
    /** 返回元素名；不改变 Java 返回类型。 */
    String name();
}