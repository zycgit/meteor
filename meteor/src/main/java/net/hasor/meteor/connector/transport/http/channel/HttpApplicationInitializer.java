/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.ProtoInitializer;

/** Initializes the ordinary HTTP application pipeline together with configured protocol upgrades. */
@FunctionalInterface
public interface HttpApplicationInitializer {
    void initialize(ProtoBuildContext stack, HttpUpgradeCoordinator coordinator, ProtoInitializer ordinary);
}
