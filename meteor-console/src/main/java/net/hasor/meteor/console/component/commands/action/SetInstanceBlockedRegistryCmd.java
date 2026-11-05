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
import net.hasor.meteor.console.component.commands.dto.instance.SetInstanceBlockedRequest;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;

/** 封禁或解除封禁指定实例。 */
public final class SetInstanceBlockedRegistryCmd implements RegistryCommand<SetInstanceBlockedRequest, SuccessResult> {
    @Override
    public SuccessResult execute(CommandContext context, SetInstanceBlockedRequest request) {
        MetInstanceAdmin admin = context.instance(request.getTarget());
        if (request.isBlocked()) {
            admin.block();
        } else {
            admin.unblock();
        }
        return new SuccessResult(true);
    }
}
