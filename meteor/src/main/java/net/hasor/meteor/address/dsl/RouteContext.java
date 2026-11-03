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
import java.util.Set;

/** 一次选址的输入，由调用层组装；地址层不依赖 RPC 或注册中心。 */
public final class RouteContext {
    private final String              serviceId;
    private final Map<String, Object> env     = new LinkedHashMap<>();
    private final Map<String, Object> service = new LinkedHashMap<>();
    private final Map<String, Object> method  = new LinkedHashMap<>();
    private final Object[]            args;
    private       Set<String>         addressSchemes;

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

    /** 本次调用允许的地址 scheme；null 不增加限制，空集合不允许任何地址。 */
    public Set<String> getAddressSchemes() {
        return this.addressSchemes;
    }

    public void setAddressSchemes(Set<String> addressSchemes) {
        if (addressSchemes == null) {
            this.addressSchemes = null;
        } else {
            this.addressSchemes = Set.copyOf(addressSchemes);
        }
    }
}
