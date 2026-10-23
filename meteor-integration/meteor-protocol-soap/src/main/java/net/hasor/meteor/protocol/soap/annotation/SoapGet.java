/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.annotation;
import java.lang.annotation.*;

/** 将无参只读方法发布为 SOAP 1.2 GET 响应资源，地址为服务端点加 ?method=方法名。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SoapGet {
}
