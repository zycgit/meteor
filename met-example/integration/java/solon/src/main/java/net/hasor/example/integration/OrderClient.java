/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import net.hasor.meteor.MetContext;
import org.noear.solon.annotation.Inject;

/** 消费端只注入接口，RPC 代理由框架集成创建。 */
public class OrderClient {
    @Inject
    private OrderService orders;
    @Inject
    private MetContext meteor;

    public void run() throws Exception {
        OrderScenario.run(this.meteor, this.orders);
    }
}
