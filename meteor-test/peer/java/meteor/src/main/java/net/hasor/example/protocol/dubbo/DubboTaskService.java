/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo;
import net.hasor.meteor.annotation.MetMessage;

/** 业务阻塞由应用明确释放，用于展示异步调用、消息调用和正常回执的区别。 */
public interface DubboTaskService {
    String execute(String value) throws InterruptedException;

    @MetMessage
    void notify(String value) throws InterruptedException;

    String echo(String value);
}
