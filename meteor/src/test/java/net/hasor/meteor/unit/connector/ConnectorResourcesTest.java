/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ConnectorManager;
import org.junit.Test;
import static org.junit.Assert.*;

/** 管理器计时器及连接编号的生命周期契约。 */
public class ConnectorResourcesTest {
    @Test
    public void timerRejectsSchedulingBeforeInitialization() {
        // 未初始化的管理器不接受任务，失败后仍由资源块负责关闭。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            assertThrows(RejectedExecutionException.class, () -> manager.schedule(() -> {
            }, 1));
        }
    }

    @Test
    public void initializedTimerExecutesScheduledTask() throws Exception {
        // 初始化计时器后提交任务，通过完成信号等待执行。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            manager.init();
            CountDownLatch fired = new CountDownLatch(1);
            manager.schedule(fired::countDown, 1);

            // 在有界时间内收到任务回调。
            assertTrue(fired.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void timerRejectsSchedulingAfterClose() {
        // 关闭已初始化的管理器，计时器停止接收新任务。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            manager.init();
            manager.close();

            // 关闭后的错误保持为调度拒绝，而不是静默丢失任务。
            assertThrows(RejectedExecutionException.class, () -> manager.schedule(() -> {
            }, 1));
        }
    }

    @Test
    public void idsAreUniqueAcrossManagerRestart() {
        // 先取一个连接编号，再关闭同一管理器。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            manager.init();
            long first = manager.nextConnectionId();
            manager.close();

            // 重启不重置编号，避免已关闭连接与新连接冲突。
            manager.init();
            assertTrue(manager.nextConnectionId() > first);
        }
    }
}
