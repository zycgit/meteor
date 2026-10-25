/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;

public interface InteropService {
    JsonNode EmptyCall(JsonNode request);

    JsonNode UnaryCall(JsonNode request);

    JsonNode StreamingInputCall(Flow.Publisher<JsonNode> requests) throws Exception;

    Flow.Publisher<JsonNode> StreamingOutputCall(JsonNode request);

    Flow.Publisher<JsonNode> FullDuplexCall(Flow.Publisher<JsonNode> requests);

    Flow.Publisher<JsonNode> HalfDuplexCall(Flow.Publisher<JsonNode> requests) throws Exception;
}
