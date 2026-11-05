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
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoResult;
import net.hasor.meteor.registry.admin.MetClusterAdmin;

/** 读取当前注册中心的信息与可达状态。 */
public final class ClusterInfoRegistryCmd implements RegistryCommand<ClusterInfoRequest, ClusterInfoResult> {
    @Override
    public ClusterInfoResult execute(CommandContext context, ClusterInfoRequest request) {
        MetClusterAdmin admin = context.cluster();
        return new ClusterInfoResult(admin.info(), admin.status());
    }
}