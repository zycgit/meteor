/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Map;

/** Declares network routes without exposing the engine's pipeline API to applications. */
public interface RoutedReceiver<M> extends ChannelListener<M> {
    /** Routes normalized and validated by NetworkConnectorFactory.prepareRoutes. */
    Map<String, NetworkRoute<M>> routes();

    /** Outgoing route known before the first send; null means select from incoming data. */
    String initialRoute();

    void receive(String route, M message) throws Exception;
}
