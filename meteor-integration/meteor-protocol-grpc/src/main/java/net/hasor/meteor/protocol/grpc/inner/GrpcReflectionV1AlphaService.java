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

/** 标准 grpc.reflection.v1alpha.ServerReflection 契约。 */
public interface GrpcReflectionV1AlphaService {
    /** 通过双向流查询已发布服务及其 Protobuf 描述。 */
    Flow.Publisher<JsonNode> ServerReflectionInfo(Flow.Publisher<JsonNode> requests);
}
