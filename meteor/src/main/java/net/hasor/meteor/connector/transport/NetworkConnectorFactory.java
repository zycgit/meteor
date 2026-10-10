/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Map;
import net.hasor.meteor.connector.ConnectorConfig;

/** Transport-only SPI. Creating a provider does not allocate network resources. */
public interface NetworkConnectorFactory<M> {
    String name();

    Class<M> messageType();

    /** Message routes can share an accepted physical channel without closing each other. */
    default boolean sharedRoutes() {
        return false;
    }

    /** 构建阶段按挂载名校验并更新参数；此时不创建资源和消息探测器。 */
    default void configRoutes(Map<String, Map<String, String>> routes) {
    }

    NetworkConnector<M> create(ConnectorConfig config, ClassLoader loader);
}
