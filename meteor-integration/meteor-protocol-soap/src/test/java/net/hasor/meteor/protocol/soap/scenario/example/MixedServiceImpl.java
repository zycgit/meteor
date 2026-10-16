/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

import java.util.function.Consumer;

/** 普通方法返回原值，消息方法将通知交给业务处理器。 */
public final class MixedServiceImpl implements MixedService {
    private final Consumer<String> notifications;

    public MixedServiceImpl(Consumer<String> notifications) {
        this.notifications = notifications;
    }

    @Override
    public String echo(String message) {
        return message;
    }

    @Override
    public void signal(String message) {
        this.notifications.accept(message);
    }
}
