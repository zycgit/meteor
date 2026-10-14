/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Map;
import java.util.function.Function;
import net.hasor.meteor.connector.ContextPaths;

/** Route options and a non-consuming message probe, independent of application payloads. */
public final class NetworkRoute<M> {
    private final Map<String, String>     options;
    private final Function<M, RouteMatch> probe;
    private final ContextPaths            contexts;

    /** 接收已完成配置且不再修改的参数，共享协议已编译的挂载表，不重复解析配置。 */
    public NetworkRoute(Map<String, String> options, ContextPaths contexts, Function<M, RouteMatch> probe) {
        this.options = options;
        this.probe = probe;
        this.contexts = contexts;
    }

    public Map<String, String> options() {
        return this.options;
    }

    public RouteMatch match(M message) {
        return this.probe.apply(message);
    }

    public ContextPaths contexts() {
        return this.contexts;
    }
}