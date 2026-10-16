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
import java.util.concurrent.ExecutionException;
import net.hasor.meteor.address.InterAddress;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 连接管理器中未被端点与订阅场景重复覆盖的生命周期行为。 */
public class ConnectorManagerTest {
    @Test
    public void subscriptionRequiresInitialization() {
        // 构建管理器但不初始化其运行期资源。
        try (ConnectorManager manager = EndpointFixture.manager()) {
            // 订阅只能登记到已经初始化的管理器生命周期中。
            assertThrows(IllegalStateException.class, () -> manager.subscribe((channel, id, payload) -> {
            }));
        }
    }

    @Test
    public void addressFilteringDoesNotTurnLogicalNameIntoSchemeAlias() throws Exception {
        // wirea 是逻辑协议名，同时被 wireb 作为公开地址 scheme。
        Map<String, String> endpointOptions = Map.of("workerThread", "1");
        ProtocolConfig logical = new ProtocolConfig("wirea", "tcp", "wirea", Map.of());
        ProtocolConfig alias = new ProtocolConfig("wireb", "wirea", "wireb", Map.of());
        InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        ConnectorConfig config = new ConnectorConfig(//
                "client", local, endpointOptions, List.of(logical, alias), false);

        // 目标 scheme 排除了真正的 wirea 后应直接失败，不能改选 wireb。
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            InterAddress target = new InterAddress("wirea://127.0.0.1:8123/test");
            ExecutionException failure = assertThrows(//
                    ExecutionException.class, () -> manager.connect(target, "wirea").get());
            assertTrue(failure.getCause().getMessage().contains("No connector configured"));
        }
    }
}
