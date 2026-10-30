/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.oncrpc;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;
import org.acplt.oncrpc.OncRpcTcpClient;
import org.acplt.oncrpc.XdrInt;

/** RemoteTea 独立 ONC RPC 消费者，调用 Meteor 订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 直接连接本地服务，无需 rpcbind。
        URI target = OrderPeerSettings.target("tcp://127.0.0.1:8080");
        InetAddress address = InetAddress.getByName(target.getHost());
        OncRpcTcpClient client = new OncRpcTcpClient(address, SdkProviderMain.PROGRAM,
                SdkProviderMain.VERSION, target.getPort());
        try {
            client.setCharacterEncoding(StandardCharsets.US_ASCII.name());

            // 保存与查询分别对应 order.x 的过程 1、2，共用普通订单 DTO。
            Order order = OrderExampleUtils.newOrder(1001, "oncrpc-sdk");
            SdkOrderValue saved = new SdkOrderValue(null);
            client.call(1, new SdkOrderValue(order), saved);
            SdkOrderValue found = new SdkOrderValue(null);
            client.call(2, new XdrInt(1001), found);
            OrderExampleUtils.print(found.getOrder());
        } finally {
            client.close();
        }
    }
}
