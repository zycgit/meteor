/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;
import com.fasterxml.jackson.annotation.JsonIgnore;
import net.hasor.meteor.console.model.fo.ManagementTarget;

/** 带有管理目标及明确结果类型的命令请求。 */
public abstract class CommandRequest<R> {
    private final ManagementTarget target;

    protected CommandRequest(ManagementTarget target) {
        this.target = target;
    }

    public ManagementTarget getTarget() {
        return this.target;
    }

    @JsonIgnore
    public abstract CommandType getCommand();
}
