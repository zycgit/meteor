/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcMountRuntimeTest {
    @Test
    public void activeSessionsShareOneMountWorkerPoolUntilTheLastRelease() throws Exception {
        // 同一挂载保留两个会话引用，取得同一个有界 worker 池。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", Map.of("streamWorkers", "2"));
            GrpcMountRuntime runtime = new GrpcMountRuntime(config);
            ExecutorService first = (ExecutorService) runtime.retain(context).workers();
            ExecutorService second = (ExecutorService) runtime.retain(context).workers();
            assertSame(first, second);

            // 第一个会话释放不关闭共享资源，最后一个会话释放才结束该池。
            runtime.release();
            assertFalse(first.isShutdown());
            runtime.release();
            assertTrue(first.isShutdown());

            // 之后的新连接获得同一挂载的新一代 worker 池。
            ExecutorService next = (ExecutorService) runtime.retain(context).workers();
            assertNotSame(first, next);
            runtime.release();
            assertTrue(next.isShutdown());
        }
    }
}
