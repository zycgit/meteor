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
import net.hasor.web.annotation.Get;
import net.hasor.web.annotation.MappingTo;
import net.hasor.web.annotation.Put;

/** 指定实例的信息与阻断控制。 */
@MappingTo("/api")
public class InstanceController {
    @Inject
    private GovernanceService governance;

    @Get
    @MappingTo("/instance")
    public Object info(Invoker invoker) {
        return this.governance.instanceInfo(ConsoleRequest.from(invoker).target());
    }

    @Put
    @MappingTo("/instance/block")
    public Object block(Invoker invoker) {
        ConsoleRequest request = ConsoleRequest.from(invoker);
        return this.governance.instanceBlocked(request.target(), request.flag("blocked"));
    }
}
