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
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.directory.ApplicationEntry;
import net.hasor.meteor.console.component.commands.dto.directory.ApplicationsRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ApplicationsResult;
import net.hasor.meteor.registry.admin.MetPage;

/** 分页查询应用目录。 */
public final class ApplicationsRegistryCmd implements RegistryCommand<ApplicationsRequest, ApplicationsResult> {
    @Override
    public ApplicationsResult execute(CommandContext context, ApplicationsRequest request) {
        MetPage<String> page = context.cluster().applications(request.getTarget().directoryQuery());
        List<ApplicationEntry> items = new ArrayList<>();
        for (String name : page.getItems()) {
            items.add(new ApplicationEntry(name));
        }
        return new ApplicationsResult(items, page.getNextCursor());
    }
}