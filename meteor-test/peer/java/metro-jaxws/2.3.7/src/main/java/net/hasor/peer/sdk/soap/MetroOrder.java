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
import net.hasor.example.Order;

/** Metro 的 JAXB 线上模型；共享业务 DTO 保持协议无关。 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "Order", namespace = MetroOrderService.NAMESPACE, propOrder = {"id", "description", "attachment"})
public final class MetroOrder {
    @XmlElement(namespace = "")
    private int id;

    @XmlElement(namespace = "", nillable = true)
    private String description;

    @XmlElement(namespace = "", nillable = true)
    private byte[] attachment;

    public static MetroOrder from(Order order) {
        MetroOrder value = new MetroOrder();
        value.id = order.getId();
        value.description = order.getDescription();
        value.attachment = order.getAttachment();
        return value;
    }

    public Order toOrder() {
        Order order = new Order();
        order.setId(this.id);
        order.setDescription(this.description);
        order.setAttachment(this.attachment);
        return order;
    }
}
