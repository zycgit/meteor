/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.directory.ServiceEntry;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetPage;
import net.hasor.meteor.registry.admin.MetServiceInfo;

/** 分页查询服务目录，并补充注册中心维护的服务开关。 */
public final class ServicesRegistryCmd implements RegistryCommand<ServicesRequest, ServicesResult> {
    @Override
    public ServicesResult execute(CommandContext context, ServicesRequest request) {
        ManagementTarget target = request.getTarget();
        MetDirectoryQuery query = target.directoryQuery();
        boolean instanceScope = target.isDirect() || StringUtils.isNotBlank(target.getInstanceId());
        MetPage<MetServiceInfo> page;
        if (instanceScope) {
            page = context.instance(target).getServiceList(query);
        } else {
            page = context.cluster().services(query.getSearch(), query);
        }

        List<ServiceEntry> items = new ArrayList<>();
        for (MetServiceInfo info : page.getItems()) {
            Boolean enabled = null;
            if (!instanceScope) {
                enabled = context.registry().getServiceAdmin(info.getServiceId()).isEnable();
            }
            items.add(new ServiceEntry(info, enabled));
        }
        return new ServicesResult(items, page.getNextCursor());
    }
}
