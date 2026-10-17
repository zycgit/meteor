/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.builtin;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** 状态来自 Context 的上线、下线及服务发布事件；未知服务的 Watch 保持订阅。 */
final class GrpcHealthEndpoint implements GrpcHealthService {
    private static final ObjectMapper                 MAPPER   = new ObjectMapper();
    private final        GrpcBuiltinServicesExtension services;
    private final        Set<GrpcHealthSubscription>  watchers = ConcurrentHashMap.newKeySet();
    private final        Cancellable                  events;
    private volatile     boolean                      closed;

    GrpcHealthEndpoint(GrpcBuiltinServicesExtension services, MetContext context) {
        this.services = services;
        this.events = context.subscribe(event -> {
            if (event.getType() == MetContextEventType.OFFLINE) {
                this.offline();
            } else {
                this.refresh();
            }
        });
    }

    @Override
    public JsonNode Check(JsonNode request) {
        String status = this.statuses().get(request.path("service").asText(""));
        if (status == null) {
            throw new GrpcStatusException(5, "Unknown health service", null, null);
        }
        return GrpcHealthEndpoint.response(status);
    }

    @Override
    public JsonNode List(JsonNode request) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode statuses = result.putObject("statuses");
        this.statuses().forEach((name, status) -> statuses.set(name, GrpcHealthEndpoint.response(status)));
        return result;
    }

    @Override
    public Flow.Publisher<JsonNode> Watch(JsonNode request) {
        String name = request.path("service").asText("");
        return subscriber -> {
            GrpcHealthSubscription watch = new GrpcHealthSubscription(name, subscriber, this.watchers::remove);
            this.watchers.add(watch);
            subscriber.onSubscribe(watch);
            // 登记后读取状态，避免订阅与事件之间遗漏最后一次变更。
            watch.update(this.statuses().getOrDefault(name, "SERVICE_UNKNOWN"));
            if (this.closed || !this.services.online()) {
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
        for (GrpcHealthSubscription watch : this.watchers) {
            watch.update(statuses.getOrDefault(watch.name(), "SERVICE_UNKNOWN"));
        }
    }

    void offline() {
        Map<String, String> statuses = this.statuses();
        for (GrpcHealthSubscription watch : this.watchers) {
            // 先递送最后的 NOT_SERVING，再结束长流，让入站连接可以完成排空。
            watch.update(statuses.getOrDefault(watch.name(), "SERVICE_UNKNOWN"));
            watch.close();
        }
        this.watchers.clear();
    }

    void close() {
        this.closed = true;
        this.events.cancel();
        for (GrpcHealthSubscription watch : this.watchers) {
            watch.close();
        }
        this.watchers.clear();
    }

    static JsonNode response(String status) {
        return MAPPER.createObjectNode().put("status", status);
    }
}
