/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework.hasor;
import java.nio.file.Path;
import net.hasor.core.Inject;
import net.hasor.example.integration.FrameworkOrderService;
import net.hasor.meteor.MetContext;
import net.hasor.peer.framework.FrameworkPeerUtils;

/** 普通业务 Bean，只注入接口，RPC 代理由框架集成创建。 */
public class OrderClient {
    @Inject
    private FrameworkOrderService orders;
    @Inject
    private MetContext            meteor;

    public void invoke(Path result) throws Exception {
        FrameworkPeerUtils.invoke(this.meteor, this.orders, result);
    }
}
