/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import net.hasor.meteor.MetContext;

/** 仅在 test 源中访问包内契约，不包含断言或测试流程。 */
public final class RpcTestAccess {
    private RpcTestAccess() {
    }

    public static MetContext getContext(MetCaller caller) {
        return caller.getContext();
    }
}
