/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;

public interface ConnectorFactory {
    /** Assemble a connector using its configuration and manager capabilities. */
    Connector create(ConnectorContext context) throws Exception;
}
