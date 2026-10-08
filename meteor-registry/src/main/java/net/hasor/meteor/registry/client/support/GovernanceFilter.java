/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.meteor.*;
import net.hasor.meteor.domain.ProtocolStatus;

/** 直接维护网络业务调用的阻断状态，不依赖管理 RPC 或容器查询。 */
final class GovernanceFilter implements MetFilter {
    public static final String      FILTER_ID = GovernanceFilter.class.getName();
    private final       Set<String> disabled  = ConcurrentHashMap.newKeySet();
    private volatile    boolean     block;

    @Override
    public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
        MetBindInfo<?> service = request.getBindInfo();
        if (service.isShadow()) {
            chain.doFilter(request, response);
            return;
        }

        boolean blocked = this.block;
        if (blocked || this.disabled.contains(service.getBindID())) {
            String message;
            if (blocked) {
                message = "Instance is blocked by registry policy";
            } else {
                message = "Service is disabled by registry policy";
            }
            response.sendStatus(ProtocolStatus.Forbidden, message);
            return;
        }
        chain.doFilter(request, response);
    }

    public boolean isBlock() {
        return this.block;
    }

    public void setBlock(boolean block) {
        this.block = block;
    }

    public boolean isServiceEnabled(String serviceId) {
        return !this.disabled.contains(serviceId);
    }

    public void setServiceEnabled(String serviceId, boolean enabled) {
        if (enabled) {
            this.disabled.remove(serviceId);
        } else {
            this.disabled.add(serviceId);
        }
    }

    public void retainServices(Collection<String> serviceIds) {
        this.disabled.retainAll(serviceIds);
    }

}
