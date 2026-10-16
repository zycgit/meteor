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
import net.hasor.meteor.connector.ConnectorManager;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 连接管理器定时器的所有权与生命周期。 */
public class ConnectorResourcesTest {
    @Test
    public void timerRejectsSchedulingBeforeInitialization() {
        // 创建空管理器，但不初始化它持有的定时器。
        try (ConnectorManager manager = EndpointFixture.manager()) {
            // 初始化前调度会明确失败，不能静默丢弃任务。
            assertThrows(RejectedExecutionException.class, () -> manager.schedule(() -> {
            }, 1));
        }
    }

    @Test
    public void initializedTimerExecutesScheduledTask() throws Exception {
        // 先初始化管理器持有的定时器，再提交调度任务。
        try (ConnectorManager manager = EndpointFixture.manager()) {
            manager.init();
            CountDownLatch fired = new CountDownLatch(1);
            manager.schedule(fired::countDown, 1);

            // 通过有界完成信号观察任务执行结果。
            assertTrue(fired.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void timerRejectsSchedulingAfterClose() {
        // 初始化并关闭管理器，释放它持有的定时器。
        try (ConnectorManager manager = EndpointFixture.manager()) {
            manager.init();
            manager.close();

            // 关闭后提交调度任务会被管理器生命周期边界拒绝。
            assertThrows(RejectedExecutionException.class, () -> manager.schedule(() -> {
            }, 1));
        }
    }
}
