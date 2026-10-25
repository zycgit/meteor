/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http;

/** 多个协议使用相同业务签名，协议、别名与路径由发布配置决定。 */
public interface HttpCompositionService {
    String echo(String value);
}
