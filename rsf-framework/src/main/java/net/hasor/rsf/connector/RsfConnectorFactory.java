/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;

public interface RsfConnectorFactory {
    /** Transport listenType served by this SPI provider, for example tcp or http. */
    String name();

    /** Assemble a connector using its configuration and manager capabilities.
     * AbstractConnector provides close preparation and a doClose resource-release hook.
     */
    RsfConnector create(ConnectorConfig config, ConnectorManager manager) throws Exception;
}
