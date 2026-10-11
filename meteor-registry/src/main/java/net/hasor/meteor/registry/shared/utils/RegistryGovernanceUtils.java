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
import java.util.Objects;
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

    public static final EntityRef GLOBAL = new EntityRef(EntityType.GLOBAL, "default");

    public static EntityRef service(String id) {
        return new EntityRef(EntityType.SERVICE, id);
    }

    public static EntityRef instance(String id) {
        return new EntityRef(EntityType.INSTANCE, id);
    }

    public static EntityRef localService(String instanceId, String serviceId) {
        return new EntityRef(EntityType.SERVICE, serviceId, instance(instanceId), EntitySection.CONFIGURATION);
    }

    public static EntityRef runtime(EntityRef ref) {
        return new EntityRef(ref.type(), ref.id(), ref.parent(), EntitySection.RUNTIME);
    }

    public static EntityRef ruleRef(EntityRef owner, RuleTypeData type) {
        return new EntityRef(EntityType.RULE, Objects.requireNonNull(type, "rule type").name(), owner, EntitySection.CONFIGURATION);
    }

    /** 读取某个对象下的配置与规则，规则的开关与正文在同一条记录。 */
    public static ConfigurationData read(StorageAccess storage, EntityRef owner) throws Exception {
        ConfigurationData result = new ConfigurationData();
        EntityRecord configuration = storage.getEntity(owner);
        if (configuration != null && configuration.getData() != null) {
            if (owner.type() == EntityType.GLOBAL) {
                EntGlobalConfigurationData data = (EntGlobalConfigurationData) configuration.getData();
                result.setMetadata(data.getMetadata().isEmpty() ? null : data.getMetadata());
            } else if (owner.type() == EntityType.SERVICE) {
                EntServiceConfigurationData data = (EntServiceConfigurationData) configuration.getData();
                result.setEnabled(data.getEnabled());
                result.setMetadata(data.getMetadata().isEmpty() ? null : data.getMetadata());
            }
        }
        EntityQuery query = new EntityQuery();
        query.setType(EntityType.RULE);
        query.setParent(owner);
        for (EntityRecord record : entities(storage, query)) {
            RuleTypeData type = RuleTypeData.valueOf(record.getId());
            EntRuleConfigurationData rule = (EntRuleConfigurationData) record.getData();
            setRule(result, type, rule.getRule());
            result.getRuleEnabled().put(type, rule.isEnabled());
        }
        return result;
    }

    public static List<EntityRecord> entities(StorageAccess storage, EntityQuery query) throws Exception {
        List<EntityRecord> result = new ArrayList<>();
        query.setLimit(1000);
        do {
            RecordPage<EntityRecord> page = storage.findEntities(query);
            result.addAll(page.records());
            query.setCursor(page.nextCursor());
        } while (query.getCursor() != null);
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

    ////////////////////////////////////////////////////////////////////////////////////////
    // 关系查询
    public static List<RelationRecord> relations(StorageAccess storage, RelationQuery query) throws Exception {
        List<RelationRecord> result = new ArrayList<>();
        query.setCursor(null);
        query.setLimit(1000);

        do {
            RecordPage<RelationRecord> page = storage.findRelations(query);
            result.addAll(page.records());
            query.setCursor(page.nextCursor());
        } while (query.getCursor() != null);
        return result;
    }

}
