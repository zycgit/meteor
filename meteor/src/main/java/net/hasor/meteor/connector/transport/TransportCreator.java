/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;

/** Creates a network connector from prepared transport input and runtime services. */
@FunctionalInterface
public interface TransportCreator<P, M> {
    NetworkConnector<M> create(P prepared, TransportRuntimeServices runtimeServices);
}
