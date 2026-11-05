/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;

/** 内部无状态命令契约；请求类型同时限定执行结果类型。 */
public interface RegistryCommand<Q extends CommandRequest<R>, R> {
    R execute(CommandContext context, Q request);
}
