/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.rpc.support;
import java.util.Collection;
import java.util.Collections;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetConnector;
import net.hasor.meteor.connector.MetConnectorFactory;

/** 用于空连接器配置；测试通过 ConnectorManager 契约注入受控通道。 */
public final class UnusedConnectorFactory implements MetConnectorFactory {
    @Override
    public Collection<String> listenTypes(ClassLoader loader) {
        return Collections.emptyList();
    }

    @Override
    public MetConnector create(ConnectorConfig config, ConnectorManager manager) {
        throw new IllegalStateException("No connector is configured for this contract test");
    }
}
