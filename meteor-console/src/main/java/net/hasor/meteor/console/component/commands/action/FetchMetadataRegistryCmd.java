/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands.action;
import java.util.Map;
import net.hasor.meteor.console.component.commands.CommandContext;
import net.hasor.meteor.console.component.commands.RegistryCommand;
import net.hasor.meteor.console.component.commands.dto.metadata.FetchMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.MetadataResult;
import net.hasor.meteor.console.model.fo.ManagementTarget;

/** 读取集群或服务元信息。 */
public final class FetchMetadataRegistryCmd implements RegistryCommand<FetchMetadataRequest, MetadataResult> {
    @Override
    public MetadataResult execute(CommandContext context, FetchMetadataRequest request) {
        ManagementTarget target = request.getTarget();
        Map<String, Object> values = switch (context.scope(target)) {
            case "cluster" -> context.cluster().fetchMeta();
            case "service" -> context.service(target).fetchMeta();
            default -> throw new UnsupportedOperationException("元数据查询仅支持集群或服务范围");
        };
        return new MetadataResult(values);
    }
}
