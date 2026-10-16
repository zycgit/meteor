/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.rsfs.support;
import net.hasor.meteor.MetMessage;

public interface RsfsBlockingService {
    String execute() throws InterruptedException;

    @MetMessage
    void signal() throws InterruptedException;
}
