/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.lang.annotation.*;

/**
 * 标记发布接口中不作为 RPC 服务暴露的方法，不影响普通 Java 对象的直接调用。
 * 注解以注册的服务类型为准；接口发布时，仅标在实现类上不生效。
 * 继承方法沿用原声明，重写方法时需重新标注。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MetIgnore {
}
