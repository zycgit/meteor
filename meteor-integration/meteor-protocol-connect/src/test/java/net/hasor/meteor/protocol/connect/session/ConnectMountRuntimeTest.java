/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectMountRuntimeTest {
    @Test
    public void activeSessionsShareMountWorkersUntilLastRelease() throws Exception {
        // 同一挂载的两条会话复用 worker，单个会话退出不会中断另一条会话。
        try (MetContext context = new MetConfiguration().buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of("streamWorkers", "2"));
            ConnectMountRuntime runtime = new ConnectMountRuntime(config);
            ExecutorService first = (ExecutorService) runtime.retain(context).workers();
            ExecutorService second = (ExecutorService) runtime.retain(context).workers();
            try {
                assertSame(first, second);
                runtime.release();
                assertFalse(first.isShutdown());

                // 最后一条会话退出后才释放挂载创建的线程池。
                runtime.release();
                assertTrue(first.isShutdown());
            } finally {
                runtime.release();
                runtime.release();
            }
        }
    }

    @Test
    public void releasedMountCreatesFreshWorkersForLaterSession() throws Exception {
        // 挂载没有活动会话后可再次接入连接，新会话得到新的 worker 池。
        try (MetContext context = new MetConfiguration().buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
            ConnectMountRuntime runtime = new ConnectMountRuntime(config);
            ExecutorService previous = (ExecutorService) runtime.retain(context).workers();
            runtime.release();
            ExecutorService current = (ExecutorService) runtime.retain(context).workers();
            try {
                assertTrue(previous.isShutdown());
                assertNotSame(previous, current);
                assertFalse(current.isShutdown());
            } finally {
                runtime.release();
            }
        }
    }
}
