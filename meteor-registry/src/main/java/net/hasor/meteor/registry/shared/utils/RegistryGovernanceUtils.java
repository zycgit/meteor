/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.utils;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.dsl.FlowRuleParser;
import net.hasor.meteor.address.dsl.XmlRuleParser;
import net.hasor.meteor.registry.shared.rpc.ConfigurationData;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 注册中心治理规则校验、配置记录定位与查询。 */
public final class RegistryGovernanceUtils {

    private RegistryGovernanceUtils() {
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 治理规则校验
    public static ValidationData validateRule(RuleTypeData type, String rule) {
        List<String> errors = new ArrayList<>();
        if (type == null) {
            errors.add("Rule type is required");
        } else if (StringUtils.isNotBlank(rule)) {
            try {
                if (type == RuleTypeData.FLOW_CONTROL) {
                    new FlowRuleParser().parse(rule);
                } else {
                    new XmlRuleParser().parse(rule);
                }
            } catch (IllegalArgumentException failure) {
                errors.add("Invalid " + type + ": " + failure.getMessage());
            }
        }

        ValidationData result = new ValidationData();
        result.setErrors(errors.toArray(String[]::new));
        return result;
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 配置记录定位与读取

    /** 仅读取一个明确治理范围；各项独立读取，不遍历实体或成员。 */
    public static ConfigurationData read(StorageAccess storage, String instanceId, String serviceId) throws Exception {
        ConfigurationData result = new ConfigurationData();
        EntityRecord<EntConfigurationData> state = storage.getConfiguration(instanceId, serviceId);
        if (state != null) {
            result.setEnabled(state.getData().getEnabled());
        }

        EntityRecord<EntMetadataData> metadata = storage.getMetadata(instanceId, serviceId);
        if (metadata != null && !metadata.getData().getMetadata().isEmpty()) {
            result.setMetadata(metadata.getData().getMetadata());
        }

        for (RuleKind kind : RuleKind.values()) {
            EntityRecord<EntRuleConfigurationData> record = storage.getRule(instanceId, serviceId, kind);
            if (record != null) {
                RuleTypeData type = RuleTypeData.valueOf(kind.name());
                setRule(result, type, record.getData().getRule());
                result.getRuleEnabled().put(type, record.getData().isEnabled());
            }
        }
        return result;
    }

    private static void setRule(ConfigurationData configuration, RuleTypeData type, String rule) {
        if (type == null) {
            throw new IllegalArgumentException("Rule type is required");
        }

        switch (type) {
            case SERVICE_ROUTE -> configuration.setServiceRoute(rule);
            case METHOD_ROUTE -> configuration.setMethodRoute(rule);
            case ARGS_ROUTE -> configuration.setArgsRoute(rule);
            case FLOW_CONTROL -> configuration.setFlowControl(rule);
        }
    }

}
