/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.builtin;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;

/** v1alpha 独立发布，复用 v1 的描述查询逻辑，报文由各自绑定的 proto 编解码。 */
final class GrpcReflectionV1AlphaEndpoint implements GrpcReflectionV1AlphaService {
    private final GrpcReflectionService reflection;

    GrpcReflectionV1AlphaEndpoint(GrpcReflectionService reflection) {
        this.reflection = reflection;
    }

    @Override
    public Flow.Publisher<JsonNode> ServerReflectionInfo(Flow.Publisher<JsonNode> requests) {
        return this.reflection.ServerReflectionInfo(requests);
    }
}
