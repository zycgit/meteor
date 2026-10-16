/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** 连接器单元测试共享的端点数据。 */
final class EndpointFixture {
    private EndpointFixture() {
    }

    static InterAddress address(String scheme) {
        return new InterAddress(scheme, "127.0.0.1", 1234, "test");
    }

    static ConnectorConfig rsfEndpoint(String name, boolean bindEnabled) {
        Map<String, String> options = Map.of("workerThread", "1");
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Map.of());
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        return new ConnectorConfig(name, address, options, List.of(protocol), bindEnabled);
    }

    static ConnectorManager manager(ConnectorConfig... configs) {
        MetContext context = ConnectorTestSupport.sharedContext(configs);
        return new ConnectorManager(context);
    }
}
