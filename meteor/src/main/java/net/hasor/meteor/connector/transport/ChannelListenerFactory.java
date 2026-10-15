/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;

/** Creates the listener after close observation is installed and before channel events are delivered. */
public interface ChannelListenerFactory<M> {
    ChannelListener<M> createListener(NetworkChannel<M> channel) throws Exception;
}
