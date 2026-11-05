/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.service.MembersRequest;
import net.hasor.meteor.console.component.commands.dto.service.MembersResult;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetPage;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceMember;

/** 按角色分页查询服务成员。 */
public final class MembersRegistryCmd implements RegistryCommand<MembersRequest, MembersResult> {
    @Override
    public MembersResult execute(CommandContext context, MembersRequest request) {
        MetServiceAdmin admin = context.service(request.getTarget());
        MetDirectoryQuery query = request.getTarget().directoryQuery();
        MetPage<MetServiceMember> page;
        if (request.getRole() == MetServiceType.Provider) {
            page = admin.providers(null, query);
        } else {
            page = admin.consumers(null, query);
        }
        return new MembersResult(page.getItems(), page.getNextCursor());
    }
}
