/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.web;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.core.Inject;
import net.hasor.meteor.console.component.commands.dto.service.ServiceInfoResult;
import net.hasor.meteor.console.global.http.ConsoleRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.console.service.GovernanceService;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.web.Invoker;
import net.hasor.web.annotation.Get;
import net.hasor.web.annotation.MappingTo;
import net.hasor.web.annotation.Put;

/** 服务声明、成员查询与服务启停。 */
@MappingTo("/api")
public class ServiceController {
    @Inject
    private GovernanceService governance;

    @Get
    @MappingTo("/service")
    public Object info(Invoker invoker) {
        ManagementTarget target = ConsoleRequest.from(invoker).target();
        ServiceInfoResult detail = this.governance.serviceInfo(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("info", detail.info());
        result.put("enabled", detail.enabled());
        if (target.isDirect() || StringUtils.isNotBlank(target.getInstanceId())) {
            result.put("description", detail.description());
        }
        return result;
    }

    @Get
    @MappingTo("/service/members")
    public Object members(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        MetServiceType role = switch (request.required("role")) {
            case "providers" -> MetServiceType.Provider;
            case "consumers" -> MetServiceType.Consumer;
            default -> throw new IllegalArgumentException("role 必须为 providers 或 consumers");
        };
        return this.governance.members(request.target(), role);
    }

    @Put
    @MappingTo("/service/enabled")
    public Object enabled(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.serviceEnabled(request.target(), request.flag("enabled"));
    }
}
