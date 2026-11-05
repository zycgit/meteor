/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.directory.UnitsRequest;
import net.hasor.meteor.console.component.commands.dto.directory.UnitsResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;

/** 查询服务或应用的单元目录。 */
public final class UnitsRegistryCmd implements RegistryCommand<UnitsRequest, UnitsResult> {
    @Override
    public UnitsResult execute(CommandContext context, UnitsRequest request) {
        ManagementTarget target = request.getTarget();
        if (StringUtils.isNotBlank(target.getServiceId())) {
            return new UnitsResult(context.service(target).units());
        }
        return new UnitsResult(context.cluster().units(target.getApplicationName()));
    }
}
