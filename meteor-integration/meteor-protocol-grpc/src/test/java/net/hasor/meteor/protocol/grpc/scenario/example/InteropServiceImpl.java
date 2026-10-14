/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;

public final class InteropServiceImpl implements InteropService {
    private final ExecutorService delivery;

    public InteropServiceImpl(ExecutorService delivery) {
        this.delivery = delivery;
    }

    public JsonNode EmptyCall(JsonNode request) {
        return JsonNodeFactory.instance.objectNode();
    }

    public JsonNode UnaryCall(JsonNode request) {
        this.checkStatus(request);
        return this.payload(request.path("responseSize").asInt());
    }

    public JsonNode StreamingInputCall(Flow.Publisher<JsonNode> requests) throws Exception {
        // 官方互通协议要求累加每条请求的二进制正文长度。
        AtomicInteger size = new AtomicInteger();
        var consuming = MetCaller.consume(requests, request -> {
            try {
                byte[] bytes = request.path("payload").path("body").binaryValue();
                if (bytes != null) {
                    size.addAndGet(bytes.length);
                }
            } catch (Exception failure) {
                throw new IllegalArgumentException(failure);
            }
        });

        // 请求流完成后才返回汇总结果，不在订阅回调内等待。
        consuming.get(10, TimeUnit.SECONDS);
        return JsonNodeFactory.instance.objectNode().put("aggregatedPayloadSize", size.get());
    }

    public Flow.Publisher<JsonNode> StreamingOutputCall(JsonNode request) {
        return MetCaller.fromIterable(this.responses(request));
    }

    public Flow.Publisher<JsonNode> FullDuplexCall(Flow.Publisher<JsonNode> requests) {
        return subscriber -> {
            // 输出订阅与输入消费并行，容量 1 使慢订阅者施加反压。
            SubmissionPublisher<JsonNode> output = new SubmissionPublisher<>(this.delivery, 1);
            output.subscribe(subscriber);
            var reading = MetCaller.consume(requests, request -> {
                if (output.isClosed()) {
                    return;
                }
                try {
                    for (JsonNode response : this.responses(request)) {
                        output.submit(response);
                    }
                } catch (GrpcStatusException failure) {
                    // 这是服务返回的状态，不能让消费回调抛错并取消整个输入流。
                    output.closeExceptionally(failure);
                }
            });

            // 输入结束后，输出以相同的成功或失败结果结束。
            reading.onFinal(done -> {
                if (done.getCause() == null) {
                    output.close();
                } else {
                    output.closeExceptionally(done.getCause());
                }
            });
        };
    }

    public Flow.Publisher<JsonNode> HalfDuplexCall(Flow.Publisher<JsonNode> requests) throws Exception {
        // 半双工先收齐所有请求，再发布响应流。
        List<JsonNode> responses = new ArrayList<>();
        var consuming = MetCaller.consume(requests, request -> responses.addAll(this.responses(request)));
        consuming.get(10, TimeUnit.SECONDS);
        return MetCaller.fromIterable(responses);
    }

    private List<JsonNode> responses(JsonNode request) {
        // 一条互通请求可以指定多条响应各自的正文大小。
        this.checkStatus(request);
        List<JsonNode> responses = new ArrayList<>();
        for (JsonNode parameter : request.path("responseParameters")) {
            responses.add(this.payload(parameter.path("size").asInt()));
        }
        return responses;
    }

    private JsonNode payload(int size) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.putObject("payload").put("body", new byte[size]);
        return response;
    }

    private void checkStatus(JsonNode request) {
        // 对端可以要求服务端返回指定的 gRPC 业务错误。
        JsonNode status = request.path("responseStatus");
        int code = status.path("code").asInt();
        if (code != 0) {
            throw new GrpcStatusException(code, status.path("message").asText(), null, null);
        }
    }
}
