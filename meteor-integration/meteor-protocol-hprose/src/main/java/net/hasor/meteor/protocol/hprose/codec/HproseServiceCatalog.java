/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;

/** 查询当前 Hprose 挂载点可见的服务方法。 */
final class HproseServiceCatalog {
    private final MetContext     context;
    private final ProtocolConfig protocol;

    HproseServiceCatalog(MetContext context, ProtocolConfig protocol) {
        this.context = context;
        this.protocol = protocol;
    }

    String[] listFunctions(RouteMount mount) {
        Set<String> functions = new LinkedHashSet<>();
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(serviceId);
            if (!this.isProvider(service) || !this.protocol.accepts(service, mount)) {
                continue;
            }

            String alias = service.getAliasName(HproseSerializeCoder.SERIALIZE_TYPE);
            if (StringUtils.isBlank(alias)) {
                continue;
            }
            for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                functions.add(alias + "_" + method.getName());
            }
        }
        return functions.toArray(new String[functions.size()]);
    }

    ResolvedMethod resolve(String name, int parameterCount, RouteMount mount) {
        ResolvedMethod selected = null;
        boolean forbidden = false;
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(serviceId);
            if (!this.isProvider(service)) {
                continue;
            }

            String alias = service.getAliasName(HproseSerializeCoder.SERIALIZE_TYPE);
            if (StringUtils.isBlank(alias)) {
                continue;
            }
            for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                String exposedName = alias + "_" + method.getName();
                boolean signatureMatches = method.getParameterCount() == parameterCount && //
                        StringUtils.equalsIgnoreCase(exposedName, name);
                if (!signatureMatches) {
                    continue;
                }
                if (!this.protocol.accepts(service, mount)) {
                    forbidden = true;
                    continue;
                }
                if (selected != null) {
                    throw new MetException(ProtocolStatus.ProtocolError, "Ambiguous Hprose method: " + name);
                }
                selected = new ResolvedMethod(service, method);
            }
        }

        if (selected != null) {
            return selected;
        }
        if (forbidden) {
            throw new MetException(ProtocolStatus.Forbidden, "Service is not exposed on this protocol mount");
        }
        throw new MetException(ProtocolStatus.NotFound, "Hprose method not found: " + name + "/" + parameterCount);
    }

    private boolean isProvider(MetBindInfo<?> service) {
        return service != null && !service.isShadow() && service.getServiceType() == MetServiceType.Provider;
    }

    static final class ResolvedMethod {
        private final MetBindInfo<?> service;
        private final Method         method;

        ResolvedMethod(MetBindInfo<?> service, Method method) {
            this.service = service;
            this.method = method;
        }

        MetBindInfo<?> service() {
            return this.service;
        }

        Method method() {
            return this.method;
        }
    }
}
