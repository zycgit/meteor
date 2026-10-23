/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;

/** 一个服务在某个协议下的原始路径声明和各连接器发布结果。 */
public final class ProtocolMeta {
    private final MetBindInfo<?>               service;
    private final ContextPath                  contextPath;
    private final Map<String, ProtocolBinding> endpoints;
    private final Object                       bindResource;

    public ProtocolMeta(MetBindInfo<?> service, ContextPath contextPath, //
            Object bindResource, Map<String, ProtocolBinding> endpoints) {
        this.service = service;
        this.contextPath = contextPath;
        this.endpoints = endpoints;
        this.bindResource = bindResource;
    }

    public MetBindInfo<?> getService() {
        return this.service;
    }

    /** 公共 Java 契约由所属服务持有，各端点共享同一份描述。 */
    public ServiceMeta getServiceMeta() {
        return ServiceMetaParser.parse(this.service.getBindType());
    }

    /** 原始路径声明；未指定时为 null，与各端点的有效默认路径分别保存。 */
    public ContextPath getContextPath() {
        return this.contextPath;
    }

    /** 按连接器名称返回发布结果。 */
    public Map<String, ProtocolBinding> getEndpoints() {
        return this.endpoints;
    }

    public ProtocolBinding getEndpoint(String connectorName) {
        return this.endpoints.get(connectorName);
    }

    /** 当前协议绑定的本地资源；未绑定时返回 null。 */
    public Object getBindResource() {
        return this.bindResource;
    }

    /** 合并当前端点的默认路径，一次创建包含定义和描述的绑定；原始路径声明保持不变。 */
    public ProtocolBinding build(ProtocolConfig config, Object definition, ObjectNode description) {
        ContextPath effective = this.contextPath;
        if (effective == null) {
            String defaultPath = config.option("contextPath", null);
            if (defaultPath != null) {
                effective = new ContextPath(List.of(defaultPath), false);
            }
        }

        if (description != null) {
            description.put("contextExact", effective != null && effective.isExact());
        }
        return new ProtocolBinding(effective, definition, description);
    }

    /** 按公共声明导出命名协议的基本操作映射，专属契约由协议提供者补充。 */
    public ObjectNode createServiceDescription(ProtocolConfig config, String defaultSerialize) {
        ObjectNode description = JsonNodeFactory.instance.objectNode();
        String alias = this.service.getAliasName(config.name());
        description.put("name", alias == null ? this.service.getBindName() : alias);
        String coding = this.service.getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", defaultSerialize);
        }
        description.put("serializeType", coding);
        ArrayNode methods = description.putArray("methods");
        for (MethodMeta method : this.getServiceMeta().getMethods()) {
            if (method.getJavaMethod().isBridge() || method.getJavaMethod().isSynthetic()) {
                continue;
            }
            ObjectNode operation = methods.addObject();
            operation.put("name", method.getName());
            operation.put("javaSignature", method.getJavaSignature());
            operation.put("message", this.service.isMessage() || method.isMessage());
        }

        return description;
    }

    /** 普通命名协议直接使用本端点已准备的操作表，不重新猜测线上名称。 */
    public ObjectNode createServiceDescription(ProtocolConfig config, String defaultSerialize, //
            Map<Method, String> operations) {
        ObjectNode description = this.createServiceDescription(config, defaultSerialize);
        ArrayNode methods = description.putArray("methods");
        for (Map.Entry<Method, String> entry : operations.entrySet()) {
            MethodMeta method = this.getServiceMeta().getMethod(entry.getKey());
            ObjectNode operation = methods.addObject();
            operation.put("name", entry.getValue());
            operation.put("javaSignature", method.getJavaSignature());
            operation.put("message", this.service.isMessage() || method.isMessage());
        }
        return description;
    }
}
