/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework.spring;
import java.nio.file.Path;
import net.hasor.example.integration.FrameworkOrderService;
import net.hasor.meteor.MetContext;
import net.hasor.peer.framework.FrameworkPeerUtils;
import org.springframework.beans.factory.annotation.Autowired;

/** 普通业务 Bean，只注入接口，RPC 代理由框架集成创建。 */
public class OrderClient {
    @Autowired
    private FrameworkOrderService orders;
    @Autowired
    private MetContext            meteor;

    public void invoke(Path result) throws Exception {
        FrameworkPeerUtils.invoke(this.meteor, this.orders, result);
    }
}
