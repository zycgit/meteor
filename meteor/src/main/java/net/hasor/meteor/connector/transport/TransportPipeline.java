/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.meteor.connector.ConnectorConfig;

/**
 * Builds per-connection transport stages before the application codec, in inbound order.
 * Both accepted and initiated connections use this hook; getChannel().isClient() identifies the side.
 * A provider must create fresh stateful handlers per invocation.
 */
public interface TransportPipeline {
    void initialize(ConnectorConfig config, ProtoBuildContext stack) throws Exception;
}
