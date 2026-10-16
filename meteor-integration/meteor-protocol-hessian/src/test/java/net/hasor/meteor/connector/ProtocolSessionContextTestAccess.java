/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** 最小测试桥接，仅开放协议会话上下文的包内构造。 */
public final class ProtocolSessionContextTestAccess {
    private ProtocolSessionContextTestAccess() {
    }

    public static ProtocolSessionContext newContext( //
            MetContext context, BiFunction<Runnable, Long, Cancellable> scheduler, //
            InterAddress bindAddress, boolean outbound) {
        return new ProtocolSessionContext(context, scheduler, bindAddress, outbound);
    }
}
