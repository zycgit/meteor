/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.EventListener;

/** 本地生命周期与服务事件监听器；同步回调应快速返回，耗时操作由监听器自行调度。 */
@FunctionalInterface
public interface MetContextListener extends EventListener {
    /** 接收已经发生的生命周期或服务变化。 */
    void onEvent(MetContextEvent event);
}