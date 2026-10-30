/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import net.hasor.example.Order;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;

/** 严格按 order.x 的字段顺序编码，指针字段用存在标志保留空值。 */
public final class OrderXdrCodec implements XdrCodec<Order> {
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_ATTACHMENT  = 65536;

    @Override
    public Order read(XdrInput input) {
        // 订单本身是必填结构；描述和附件分别对应 IDL 中的可空指针。
        Order order = new Order();
        order.setId(input.readInt());
        if (input.readBoolean()) {
            order.setDescription(input.readString(MAX_DESCRIPTION));
        }
        if (input.readBoolean()) {
            order.setAttachment(input.readOpaque(MAX_ATTACHMENT));
        }
        return order;
    }

    @Override
    public void write(XdrOutput output, Order order) {
        // 描述按 RFC 4506 的 ASCII 字符串传输，空值与空字符串分别编码。
        output.writeInt(order.getId());
        String description = order.getDescription();
        output.writeBoolean(description != null);
        if (description != null) {
            output.writeString(description, MAX_DESCRIPTION);
        }

        // 附件按变长 opaque 传输，空值与零长度字节数组分别编码。
        byte[] attachment = order.getAttachment();
        output.writeBoolean(attachment != null);
        if (attachment != null) {
            output.writeOpaque(attachment, MAX_ATTACHMENT);
        }
    }
}
