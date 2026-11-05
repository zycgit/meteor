/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.web;
import net.hasor.core.Inject;
import net.hasor.meteor.console.global.http.ConsoleRequest;
import net.hasor.meteor.console.service.GovernanceService;
import net.hasor.web.Invoker;
import net.hasor.web.annotation.*;

/** 全局、实例与服务范围的规则查询、校验与更新。 */
@MappingTo("/api")
public class RuleController {
    @Inject
    private GovernanceService governance;

    @Get
    @MappingTo("/rule")
    public Object fetch(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.rule(request.target(), request.ruleType());
    }

    @Post
    @MappingTo("/rule/validate")
    public Object validate(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.validateRule(request.target(), request.ruleType(), request.text("rule"));
    }

    @Put
    @MappingTo("/rule")
    public Object update(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.updateRule(request.target(), request.ruleType(), request.text("rule"));
    }

    @HttpMethod("DELETE")
    @MappingTo("/rule")
    public Object reset(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.resetRule(request.target(), request.ruleType());
    }

    @Put
    @MappingTo("/rule/enabled")
    public Object enabled(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.ruleEnabled(request.target(), request.ruleType(), request.flag("enabled"));
    }
}
