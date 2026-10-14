/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.inner;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;

/** 标准 grpc.health.v1.Health 契约；消息按 proto 定义映射为 JSON 树。 */
public interface GrpcHealthService {
    /** 查询指定服务的健康状态。 */
    JsonNode Check(JsonNode request);

    /** 列出当前服务的健康状态。 */
    JsonNode List(JsonNode request);

    /** 订阅指定服务的健康状态变化。 */
    Flow.Publisher<JsonNode> Watch(JsonNode request);
}