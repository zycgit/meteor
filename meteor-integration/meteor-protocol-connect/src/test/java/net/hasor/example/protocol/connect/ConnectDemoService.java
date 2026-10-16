/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.connect;
import java.util.concurrent.Flow;

/** 同一 Java 接口同时演示普通调用与三种流式调用。 */
public interface ConnectDemoService {
    ConnectMessage echo(ConnectMessage request);

    ConnectMessage safeEcho(ConnectMessage request);

    ConnectMessage slow(ConnectMessage request);

    ConnectMessage fail(ConnectMessage request);

    Flow.Publisher<ConnectMessage> count(ConnectMessage request);

    ConnectMessage collect(Flow.Publisher<ConnectMessage> requests);

    Flow.Publisher<ConnectMessage> chat(Flow.Publisher<ConnectMessage> requests);

    Flow.Publisher<ConnectMessage> never(ConnectMessage request);

    Flow.Publisher<ConnectMessage> broken(ConnectMessage request);
}
