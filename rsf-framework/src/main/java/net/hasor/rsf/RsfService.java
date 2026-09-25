/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 宣布该接口是 RSF 接口,该接口会允许远端其它 RSF 客户端发起调用。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RsfService {
    /**服务名。*/
    String name() default "";

    /**服务分组。*/
    String group() default "";

    /**服务版本。*/
    String version() default "";

    /**获取客户端调用服务超时时间。*/
    int clientTimeout() default -1;

    /**获取序列化方式*/
    String serializeType() default "";
}