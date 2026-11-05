/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.annotation.MetService;

/** 使用公共服务声明中的名称生成 SOAP 端点及契约。 */
@MetService(name = "PublicEcho")
public interface DeclaredEcho {
    String echo(String value);
}