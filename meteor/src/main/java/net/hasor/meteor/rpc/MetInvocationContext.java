/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;

/** Binds one invocation to the current thread and restores an enclosing invocation. */
final class MetInvocationContext {
    private MetInvocationContext() {
    }

    static void invoke(MetRequest request, MetResponse response, MetFilterChain invocation) throws Throwable {
        MetRequest previousRequest = MetRequestLocal.updateLocal(request);
        MetResponse previousResponse = MetResponseLocal.updateLocal(response);
        try {
            invocation.doFilter(request, response);
        } finally {
            MetRequestLocal.updateLocal(previousRequest);
            MetResponseLocal.updateLocal(previousResponse);
        }
    }
}
