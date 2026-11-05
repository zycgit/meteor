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
import net.hasor.meteor.console.component.commands.SuccessResult;
import net.hasor.meteor.console.component.commands.dto.metadata.RemoveMetadataRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;

/** 删除指定的集群或服务元信息。 */
public final class RemoveMetadataRegistryCmd implements RegistryCommand<RemoveMetadataRequest, SuccessResult> {
    @Override
    public SuccessResult execute(CommandContext context, RemoveMetadataRequest request) {
        ManagementTarget target = request.getTarget();
        switch (context.scope(target)) {
            case "cluster" -> context.cluster().removeMeta(request.getKeys());
            case "service" -> context.service(target).removeMeta(request.getKeys());
            default -> throw new UnsupportedOperationException("元数据操作仅支持集群或服务范围");
        }
        return new SuccessResult(true);
    }
}
