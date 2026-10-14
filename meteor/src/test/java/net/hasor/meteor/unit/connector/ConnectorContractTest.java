/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.MetConnector;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorContractTest {
    @Test
    public void everyListenerStopsBeforeAnyConnectorIsReleased() throws Exception {
        // 准备两个同时持有监听器的端点。
        try (EndpointFixture fixture = new EndpointFixture("a", "b")) {
            fixture.manager.bind("a").get(5, TimeUnit.SECONDS);
            fixture.manager.bind("b").get(5, TimeUnit.SECONDS);

            // 关闭整个管理器，必须先停止全部监听器，再释放任一连接器。
            fixture.manager.close();
            assertTrue(fixture.events.contains("a:unbound"));
            assertTrue(fixture.events.contains("b:unbound"));
            assertTrue(fixture.events.contains("a:closed"));
            assertTrue(fixture.events.contains("b:closed"));
            int firstUnbound = fixture.events.indexOf("a:unbound");
            int secondUnbound = fixture.events.indexOf("b:unbound");
            int firstClosed = fixture.events.indexOf("a:closed");
            int secondClosed = fixture.events.indexOf("b:closed");
            assertTrue(firstUnbound < firstClosed);
            assertTrue(secondUnbound < firstClosed);
            assertTrue(firstUnbound < secondClosed);
            assertTrue(secondUnbound < secondClosed);
        }
    }

    @Test
    public void publicBindContractNeedsNoManagerCallback() throws Exception {
        // 公开绑定方法不要求调用方传入管理器回调。
        Method bind = MetConnector.class.getMethod("bind");
        assertEquals(0, bind.getParameterCount());
    }

    @Test
    public void publicContractHasNoManagerRegistrationCallbacks() {
        // 仅检查公开 SPI 方法，避免将内部生命周期回调作为扩展契约。
        Method[] methods = MetConnector.class.getMethods();
        String[] callbacks = { "onClosing", "onChannelConnected", "onChannelClosed" };

        // 各管理器注册回调都不应暴露在公开连接器接口中。
        for (String callback : callbacks) {
            for (Method method : methods) {
                assertFalse(StringUtils.equals(callback, method.getName()));
            }
        }
    }
}
