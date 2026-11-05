/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.http;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.console.global.error.ConsoleHttpException;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.web.Invoker;

/** HTTP 参数只在控制台边界解析，不进入注册中心的数据模型。 */
public final class ConsoleRequest {
    private final Map<String, String> query = new LinkedHashMap<>();
    private final JsonNode            body;
    private final String              managementToken;

    ConsoleRequest(URI uri, JsonNode body, String managementToken) {
        this.body = body;
        this.managementToken = managementToken;
        String raw = uri.getRawQuery();
        if (raw == null) {
            return;
        }
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (this.query.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("重复的查询参数：" + key);
            }
        }
    }

    public String query(String name) {
        return this.query.get(name);
    }

    public String required(String name) {
        String value = this.query(name);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value;
    }

    public String text(String name) {
        JsonNode value = this.body.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("字段必须是字符串：" + name);
        }
        return value.textValue();
    }

    public boolean flag(String name) {
        JsonNode value = this.body.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("字段必须是布尔值：" + name);
        }
        return value.booleanValue();
    }

    public JsonNode body() {
        return this.body;
    }

    public boolean direct() {
        String mode = this.required("mode");
        if (StringUtils.equals("direct", mode)) {
            return true;
        }
        if (!StringUtils.equals("registry", mode)) {
            throw new IllegalArgumentException("管理模式必须为 registry 或 direct");
        }
        return false;
    }

    public static ConsoleRequest from(Invoker invoker) {
        return (ConsoleRequest) invoker.getHttpRequest().getAttribute(ConsoleRequest.class.getName());
    }

    public MetRuleType ruleType() {
        return MetRuleType.valueOf(this.required("type"));
    }

    public ManagementTarget target() {
        ManagementTarget target = new ManagementTarget();
        target.setDirect(this.direct());
        target.setClusterId(this.query("clusterId"));
        target.setInstanceId(this.query("instanceId"));
        target.setServiceId(this.query("serviceId"));
        target.setScope(this.query("scope"));
        target.setFilter(this.query("filter"));
        target.setField(this.query("field"));
        target.setProtocol(this.query("protocol"));
        target.setCursor(StringUtils.trimToNull(this.query("cursor")));
        target.setUnitName(StringUtils.trimToNull(this.query("unitName")));
        String limit = this.query("limit");
        if (limit != null) {
            target.setLimit(Integer.parseInt(limit));
        }
        target.setApplicationName(this.query("applicationName"));
        target.setTargetUnit(this.query("targetUnit"));
        target.setLocalUnit(this.query("localUnit"));
        if (target.isDirect()) {
            URI uri = URI.create(this.required("target"));
            boolean extra = uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null;
            if (extra || !InterAddress.checkFormat(uri)) {
                throw new IllegalArgumentException("管理地址格式应为 rsf://host:port/unit");
            }
            target.setTarget(new InterAddress(uri));
            if (StringUtils.isBlank(this.managementToken) || this.managementToken.length() > 32768) {
                throw new ConsoleHttpException(403, "请提供目标实例的管理凭据");
            }
            String token;
            try {
                token = new String(Base64.getDecoder().decode(this.managementToken), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                throw new ConsoleHttpException(403, "目标实例管理凭据格式不正确");
            }
            if (StringUtils.isBlank(token) || token.length() > 4096) {
                throw new ConsoleHttpException(403, "请提供目标实例的管理凭据");
            }
            target.setManagementToken(token);
        }
        return target;
    }

}
