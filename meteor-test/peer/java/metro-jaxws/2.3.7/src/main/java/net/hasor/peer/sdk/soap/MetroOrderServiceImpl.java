/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.soap;
import javax.jws.WebService;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;

/** Metro 仅负责 SOAP 绑定，订单存取复用同一份业务实现。 */
@WebService(endpointInterface = "net.hasor.peer.sdk.soap.MetroOrderService", serviceName = "orders", //
        portName = "ordersPort", targetNamespace = MetroOrderService.NAMESPACE)
public final class MetroOrderServiceImpl implements MetroOrderService {
    private final OrderDemoService orders = new OrderDemoServiceImpl();

    @Override
    public MetroOrder save(MetroOrder order) {
        return MetroOrder.from(this.orders.save(order.toOrder()));
    }

    @Override
    public MetroOrder find(MetroOrderQuery query) {
        return MetroOrder.from(this.orders.find(query.toQuery()));
    }
}
