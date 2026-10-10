/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次选址的输入，由调用层组装；地址层不依赖 RPC 或注册中心。 */
public final class RouteContext {
    private final String              serviceId;
    private final Map<String, Object> env     = new LinkedHashMap<>();
    private final Map<String, Object> service = new LinkedHashMap<>();
    private final Map<String, Object> method  = new LinkedHashMap<>();
    private final Object[]            args;

    public RouteContext(String serviceId, String methodName, Object[] args) {
        this.serviceId = serviceId;
        this.service.put("id", serviceId);
        this.service.put("meta", Map.of());
        this.method.put("name", methodName);
        this.args = args == null ? new Object[0] : args;
    }

    public String getServiceId() {
        return this.serviceId;
    }

    public Map<String, Object> getEnv() {
        return this.env;
    }

    public Map<String, Object> getService() {
        return this.service;
    }

    public Map<String, Object> getMethod() {
        return this.method;
    }

    public Object[] getArgs() {
        return this.args;
    }
}
