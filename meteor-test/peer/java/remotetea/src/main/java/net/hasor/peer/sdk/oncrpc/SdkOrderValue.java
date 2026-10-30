/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.oncrpc;
import java.io.IOException;
import net.hasor.example.Order;
import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.XdrAble;
import org.acplt.oncrpc.XdrDecodingStream;
import org.acplt.oncrpc.XdrEncodingStream;

/** order.x 的订单结构，交由 RemoteTea 完成 XDR 编解码。 */
final class SdkOrderValue implements XdrAble {
    private Order order;

    SdkOrderValue(Order order) {
        this.order = order;
    }

    Order getOrder() {
        return this.order;
    }

    @Override
    public void xdrEncode(XdrEncodingStream output) throws OncRpcException, IOException {
        // 字段顺序对应 order.x，两个指针分别用存在标志保留 null。
        output.xdrEncodeInt(this.order.getId());
        String description = this.order.getDescription();
        output.xdrEncodeBoolean(description != null);
        if (description != null) {
            output.xdrEncodeString(description);
        }

        // 变长 opaque 保留附件内容，空附件与 null 分别表示。
        byte[] attachment = this.order.getAttachment();
        output.xdrEncodeBoolean(attachment != null);
        if (attachment != null) {
            output.xdrEncodeDynamicOpaque(attachment);
        }
    }

    @Override
    public void xdrDecode(XdrDecodingStream input) throws OncRpcException, IOException {
        // 从 SDK 的 XDR 流还原普通业务对象。
        Order decoded = new Order();
        decoded.setId(input.xdrDecodeInt());
        if (input.xdrDecodeBoolean()) {
            decoded.setDescription(input.xdrDecodeString());
        }
        if (input.xdrDecodeBoolean()) {
            decoded.setAttachment(input.xdrDecodeDynamicOpaque());
        }
        this.order = decoded;
    }
}
