/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider.remote;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.component.rpc.RpcTarget;
import net.hasor.meteor.console.global.error.ConsoleOperationException;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;

/** 将注册中心管理接口映射到远端 Console 的 RPC，独占出站上下文。 */
final class RemoteConnection implements RegistryAdmin {
    private final    MetContext    context;
    private final    RpcManagement management;
    private final    String        managementToken;
    private volatile boolean       closed;

    /** 工厂转入上下文所有权；RPC 代理随上下文使用，不单独关闭。 */
    RemoteConnection(MetContext context, RpcManagement management, String managementToken) {
        this.context = context;
        this.management = management;
        this.managementToken = managementToken;
    }

    @Override
    public MetClusterAdmin getClusterAdmin() {
        requireOpen();
        return new ClusterAdmin(RpcTarget.cluster());
    }

    @Override
    public MetInstanceAdmin getInstanceAdmin(String instanceId) {
        requireOpen();
        return new InstanceAdmin(RpcTarget.instance(instanceId));
    }

    @Override
    public MetServiceAdmin getServiceAdmin(String serviceId) {
        requireOpen();
        return new ServiceAdmin(RpcTarget.service(serviceId, null));
    }

    @Override
    public MetServiceAdmin getServiceAdmin(String serviceId, String instanceId) {
        requireOpen();
        if (StringUtils.isBlank(instanceId)) {
            throw new IllegalArgumentException("Instance ID must not be blank");
        }
        return new ServiceAdmin(RpcTarget.service(serviceId, instanceId));
    }

    @Override
    public synchronized void close() throws Exception {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.context.close();
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Remote registry administration is closed");
        }
    }

    /** 隐去当前连接凭据，保留原异常供 HTTP 和级联 RPC 边界识别状态。 */
    private <T> T call(Supplier<T> operation) {
        requireOpen();
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while ((cause instanceof UndeclaredThrowableException || cause instanceof InvocationTargetException || cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String message = cause.getMessage();
            if (StringUtils.isBlank(message)) {
                message = cause.getClass().getSimpleName();
            }
            if (StringUtils.isNotBlank(this.managementToken)) {
                message = message.replace(this.managementToken, "***");
            }
            throw new ConsoleOperationException(message, cause);
        }
    }

    /** 与注册中心相同的输入约束，在 JSON 编码前拒绝会被省略的 null 元信息。 */
    private void validateMetadata(Map<String, Object> values) {
        if (values == null || values.size() > MetBindInfo.MAX_METADATA_SIZE) {
            throw new IllegalArgumentException("Metadata map is required and cannot exceed " + MetBindInfo.MAX_METADATA_SIZE);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException("Metadata key must be nonblank and value must not be null");
            }
        }
    }

    private final class ClusterAdmin implements MetClusterAdmin {
        private final RpcTarget target;

        private ClusterAdmin(RpcTarget target) {
            this.target = target;
        }

        @Override
        public RegistryInfo info() {
            return call(() -> management.registryInfo(this.target));
        }

        @Override
        public RegistryStatus status() {
            return call(() -> management.registryStatus(this.target));
        }

        @Override
        public MetPage<MetServiceInfo> services(String filter, MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.services(this.target, filter, query), new TypeReference<MetPage<MetServiceInfo>>() {
            }));
        }

        @Override
        public MetPage<String> applications(MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.applications(this.target, query), new TypeReference<MetPage<String>>() {
            }));
        }

        @Override
        public MetPage<MetInstanceInfo> instances(String applicationName, MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.instances(this.target, applicationName, query), new TypeReference<MetPage<MetInstanceInfo>>() {
            }));
        }

        @Override
        public String[] units(String application) {
            return call(() -> management.units(this.target, application));
        }

        @Override
        public String fetchRule(MetRuleType type) {
            return call(() -> management.fetchRule(this.target, type));
        }

        @Override
        public void resetRule(MetRuleType type) {
            call(() -> {
                management.resetRule(this.target, type);
                return null;
            });
        }

        @Override
        public MetValidationResult validateRule(MetRuleType type, String rule) {
            return call(() -> management.validateRule(this.target, type, rule));
        }

        @Override
        public void updateRule(MetRuleType type, String rule) {
            call(() -> {
                management.updateRule(this.target, type, rule);
                return null;
            });
        }

        @Override
        public boolean isRuleEnabled(MetRuleType type) {
            return call(() -> management.isRuleEnabled(this.target, type));
        }

        @Override
        public void enableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, true);
                return null;
            });
        }

        @Override
        public void disableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, false);
                return null;
            });
        }

        @Override
        public Map<String, Object> fetchMeta() {
            return call(() -> management.fetchMeta(this.target));
        }

        @Override
        public void putMeta(String key, Object value) {
            putMeta(Collections.singletonMap(key, value));
        }

        @Override
        public void putMeta(Map<String, Object> values) {
            call(() -> {
                validateMetadata(values);
                management.putMeta(this.target, values);
                return null;
            });
        }

        @Override
        public void removeMeta(String... keys) {
            call(() -> {
                management.removeMeta(this.target, keys);
                return null;
            });
        }
    }

    private final class InstanceAdmin implements MetInstanceAdmin {
        private final RpcTarget target;

        private InstanceAdmin(RpcTarget target) {
            this.target = target;
        }

        @Override
        public String fetchRule(MetRuleType type) {
            return call(() -> management.fetchRule(this.target, type));
        }

        @Override
        public void updateRule(MetRuleType type, String rule) {
            call(() -> {
                management.updateRule(this.target, type, rule);
                return null;
            });
        }

        @Override
        public void resetRule(MetRuleType type) {
            call(() -> {
                management.resetRule(this.target, type);
                return null;
            });
        }

        @Override
        public boolean isRuleEnabled(MetRuleType type) {
            return call(() -> management.isRuleEnabled(this.target, type));
        }

        @Override
        public void enableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, true);
                return null;
            });
        }

        @Override
        public void disableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, false);
                return null;
            });
        }

        @Override
        public MetInstanceInfo instance() {
            return call(() -> management.instanceInfo(this.target));
        }

        @Override
        public boolean isBlock() {
            return call(() -> management.isInstanceBlocked(this.target));
        }

        @Override
        public void unblock() {
            call(() -> {
                management.instanceBlocked(this.target, false);
                return null;
            });
        }

        @Override
        public void block() {
            call(() -> {
                management.instanceBlocked(this.target, true);
                return null;
            });
        }

        @Override
        public MetPage<MetServiceInfo> getServiceList(MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.services(this.target, null, query), new TypeReference<MetPage<MetServiceInfo>>() {
            }));
        }

        @Override
        public MetServiceInfo getServiceById(String serviceId) {
            return call(() -> management.serviceInfo(this.target, serviceId));
        }
    }

    private final class ServiceAdmin implements MetServiceAdmin {
        private final RpcTarget target;

        private ServiceAdmin(RpcTarget target) {
            this.target = target;
        }

        @Override
        public MetServiceInfo info() {
            return call(() -> management.serviceInfo(this.target, null));
        }

        @Override
        public Map<String, MetServiceDescription> descriptions() {
            return call(() -> JsonUtils.convertCompatibleValue(management.descriptions(this.target), new TypeReference<Map<String, MetServiceDescription>>() {
            }));
        }

        @Override
        public String[] units() {
            return call(() -> management.units(this.target, null));
        }

        @Override
        public MetPage<MetServiceMember> providers(MetEndpointFilter filter, MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.members(this.target, MetServiceType.Provider, filter, query), new TypeReference<MetPage<MetServiceMember>>() {
            }));
        }

        @Override
        public MetPage<MetServiceMember> consumers(MetEndpointFilter filter, MetDirectoryQuery query) {
            return call(() -> JsonUtils.convertCompatibleValue(management.members(this.target, MetServiceType.Consumer, filter, query), new TypeReference<MetPage<MetServiceMember>>() {
            }));
        }

        @Override
        public String fetchRule(MetRuleType type) {
            return call(() -> management.fetchRule(this.target, type));
        }

        @Override
        public void resetRule(MetRuleType type) {
            call(() -> {
                management.resetRule(this.target, type);
                return null;
            });
        }

        @Override
        public MetValidationResult validateRule(MetRuleType type, String rule) {
            return call(() -> management.validateRule(this.target, type, rule));
        }

        @Override
        public void updateRule(MetRuleType type, String rule) {
            call(() -> {
                management.updateRule(this.target, type, rule);
                return null;
            });
        }

        @Override
        public boolean isRule(MetRuleType type) {
            return call(() -> management.isRuleEnabled(this.target, type));
        }

        @Override
        public void enableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, true);
                return null;
            });
        }

        @Override
        public void disableRule(MetRuleType type) {
            call(() -> {
                management.ruleEnabled(this.target, type, false);
                return null;
            });
        }

        @Override
        public boolean isEnable() {
            return call(() -> management.isServiceEnabled(this.target));
        }

        @Override
        public void enable() {
            call(() -> {
                management.serviceEnabled(this.target, true);
                return null;
            });
        }

        @Override
        public void disable() {
            call(() -> {
                management.serviceEnabled(this.target, false);
                return null;
            });
        }

        @Override
        public Map<String, Object> fetchMeta() {
            return call(() -> management.fetchMeta(this.target));
        }

        @Override
        public void putMeta(String key, Object value) {
            putMeta(Collections.singletonMap(key, value));
        }

        @Override
        public void putMeta(Map<String, Object> values) {
            call(() -> {
                validateMetadata(values);
                management.putMeta(this.target, values);
                return null;
            });
        }

        @Override
        public void removeMeta(String... keys) {
            call(() -> {
                management.removeMeta(this.target, keys);
                return null;
            });
        }
    }
}
