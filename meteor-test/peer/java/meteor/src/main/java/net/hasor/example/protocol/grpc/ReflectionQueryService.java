/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;
import net.hasor.example.OrderQuery;

/** 同一 Java 接口按端点配置分别使用 JSON 或自动生成的 Protobuf 契约。 */
public interface ReflectionQueryService {
    OrderQuery echo(OrderQuery query);
}
