/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.inner;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** 状态来自 Context 的上线、下线及服务发布事件；未知服务的 Watch 保持订阅。 */
final class GrpcHealthServiceImpl implements GrpcHealthService {
    private final    GrpcServicesExtension services;
    private final    Set<GrpcHealthWatch>  watchers = ConcurrentHashMap.newKeySet();
    private volatile boolean               closed;

    GrpcHealthServiceImpl(GrpcServicesExtension services) {
        this.services = services;
    }

    @Override
    public JsonNode Check(JsonNode request) {
        String status = this.statuses().get(request.path("service").asText(""));
        if (status == null) {
            throw new GrpcStatusException(5, "Unknown health service", null, null);
        }
        return response(status);
    }

    @Override
    public JsonNode List(JsonNode request) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ObjectNode statuses = result.putObject("statuses");
        this.statuses().forEach((name, status) -> statuses.set(name, response(status)));
        return result;
    }

    @Override
    public Flow.Publisher<JsonNode> Watch(JsonNode request) {
        String name = request.path("service").asText("");
        return subscriber -> {
            GrpcHealthWatch watch = new GrpcHealthWatch(name, subscriber, this.watchers::remove);
            this.watchers.add(watch);
            subscriber.onSubscribe(watch);
            // 登记后读取状态，避免订阅与事件之间遗漏最后一次变更。
            watch.update(this.statuses().getOrDefault(name, "SERVICE_UNKNOWN"));
            if (this.closed) {
                watch.close();
            }
        };
    }

    private Map<String, String> statuses() {
        Map<String, String> result = new TreeMap<>();
        String status = !this.closed && this.services.online() ? "SERVING" : "NOT_SERVING";
        result.put("", status);
        for (MetBindInfo<?> service : this.services.published()) {
            result.put(service.getAliasName("grpc"), status);
        }
        return result;
    }

    void refresh() {
        Map<String, String> statuses = this.statuses();
        for (GrpcHealthWatch watch : this.watchers) {
            watch.update(statuses.getOrDefault(watch.name(), "SERVICE_UNKNOWN"));
        }
    }

    void close() {
        this.closed = true;
        for (GrpcHealthWatch watch : this.watchers) {
            watch.close();
        }
        this.watchers.clear();
    }

    static JsonNode response(String status) {
        return JsonNodeFactory.instance.objectNode().put("status", status);
    }
}
