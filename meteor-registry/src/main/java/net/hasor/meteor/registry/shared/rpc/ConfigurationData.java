/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;
import java.util.EnumMap;
import java.util.Map;

/** 聚合读取视图，不再作为整份治理配置写回存储。 */
public final class ConfigurationData {
    private Boolean                    enabled      = null;
    private String                     serviceRoute = null;
    private String                     methodRoute  = null;
    private String                     argsRoute    = null;
    private String                     flowControl  = null;
    private Map<String, Object>        metadata     = null;
    private Map<RuleTypeData, Boolean> ruleEnabled  = new EnumMap<>(RuleTypeData.class);

    public Boolean getEnabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceRoute() {
        return this.serviceRoute;
    }

    public void setServiceRoute(String serviceRoute) {
        this.serviceRoute = serviceRoute;
    }

    public String getMethodRoute() {
        return this.methodRoute;
    }

    public void setMethodRoute(String methodRoute) {
        this.methodRoute = methodRoute;
    }

    public String getArgsRoute() {
        return this.argsRoute;
    }

    public void setArgsRoute(String argsRoute) {
        this.argsRoute = argsRoute;
    }

    public String getFlowControl() {
        return this.flowControl;
    }

    public void setFlowControl(String flowControl) {
        this.flowControl = flowControl;
    }

    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<RuleTypeData, Boolean> getRuleEnabled() {
        return this.ruleEnabled;
    }

    public void setRuleEnabled(Map<RuleTypeData, Boolean> ruleEnabled) {
        this.ruleEnabled = ruleEnabled;
    }
}
