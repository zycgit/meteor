/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Collection;

/** Explicitly supplied connector assembler. This contract is not a ServiceLoader SPI. */
public interface RsfConnectorFactory {
    /** Discover the transport types this assembler can serve, without opening network resources. */
    Collection<String> listenTypes(ClassLoader loader);

    /** Assemble a connector using its configuration and manager capabilities.
     * AbstractConnector provides close preparation and a doClose resource-release hook.
     */
    RsfConnector create(ConnectorConfig config, ConnectorManager manager) throws Exception;
}
