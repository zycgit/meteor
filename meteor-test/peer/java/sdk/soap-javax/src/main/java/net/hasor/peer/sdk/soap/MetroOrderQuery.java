/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.soap;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;
import net.hasor.example.OrderQuery;

/** Metro 查询模型对应共享业务查询的订单编号。 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "OrderQuery", namespace = MetroOrderService.NAMESPACE, propOrder = {"id"})
public final class MetroOrderQuery {
    @XmlElement(namespace = "")
    private int id;

    public void setId(int id) {
        this.id = id;
    }

    public OrderQuery toQuery() {
        OrderQuery query = new OrderQuery();
        query.setId(this.id);
        return query;
    }
}
