/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;

/** 可独立查询校验和更新的治理规则类型。 */
public enum RuleTypeData {
    /** 服务级地址路由脚本。 */
    SERVICE_ROUTE,
    /** 方法级地址路由脚本。 */
    METHOD_ROUTE,
    /** 参数级地址路由脚本。 */
    ARGS_ROUTE,
    /** 流控 XML 配置。 */
    FLOW_CONTROL
}