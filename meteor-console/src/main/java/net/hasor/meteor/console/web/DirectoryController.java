/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.web;
import net.hasor.cobble.StringUtils;
import net.hasor.core.Inject;
import net.hasor.meteor.console.component.commands.dto.directory.ServiceEntry;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesResult;
import net.hasor.meteor.console.global.http.ConsoleRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.console.service.GovernanceService;
import net.hasor.meteor.registry.admin.MetPage;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.web.Invoker;
import net.hasor.web.annotation.Get;
import net.hasor.web.annotation.MappingTo;

/** 注册中心的集群信息、应用实例和服务目录。 */
@MappingTo("/api")
public class DirectoryController {
    @Inject
    private GovernanceService governance;

    @Get
    @MappingTo("/units")
    public Object units(Invoker invoker) {
        return this.governance.units(ConsoleRequest.from(invoker).target()).units();
    }

    @Get
    @MappingTo("/cluster")
    public Object cluster(Invoker invoker) {
        return this.governance.clusterInfo(ConsoleRequest.from(invoker).target());
    }

    @Get
    @MappingTo("/applications")
    public Object applications(Invoker invoker) {
        return this.governance.applications(ConsoleRequest.from(invoker).target());
    }

    @Get
    @MappingTo("/instances")
    public Object instances(Invoker invoker) {
        return this.governance.instances(ConsoleRequest.from(invoker).target());
    }

    @Get
    @MappingTo("/services")
    public Object services(Invoker invoker) {
        ManagementTarget target = ConsoleRequest.from(invoker).target();
        ServicesResult result = this.governance.services(target);
        if (target.isDirect() || StringUtils.isNotBlank(target.getInstanceId())) {
            // 实例服务目录保持原有 HTTP 结构，命令层统一使用带状态的服务条目。
            MetPage<MetServiceInfo> page = new MetPage<>();
            page.setNextCursor(result.nextCursor());
            for (ServiceEntry entry : result.items()) {
                page.getItems().add(entry.info());
            }
            return page;
        }
        return result;
    }
}
