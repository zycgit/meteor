/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 标准订单 IDL 使用 RFC 4506 ASCII string，扩展字符不能被静默替换为问号。 */
@NxnRpc(name = "oncrpc", version = "2")
public class OncRpcOrderStringTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void standardXdrStringRejectsUnicodeOrderDescription() throws Exception {
        // 通过同一套公开订单绑定配置启动真实 ONC RPC 端点。
        OrderProtocol protocol = OrderProtocol.ONCRPC;
        MetConfiguration incoming = protocol.providerConfiguration();
        MetConfiguration outgoing = protocol.consumerConfiguration();
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            protocol.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<?> subscription = protocol.subscribe(consumer);
            MetClient remote = consumer.getRsfClient(provider.getListenAddress(protocol.protocolName()));

            // 用户公开调用得到明确的标准字符串错误，而不是成功接收损坏文本。
            Order request = OrderMatrixVectors.order("unicode-order");
            Exception failure = assertThrows(Exception.class, () -> protocol.save(remote, subscription, request));
            StringBuilder details = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                details.append(cause.getMessage()).append('\n');
            }
            assertTrue(details.toString().contains("XDR string must contain ASCII characters"), details.toString());
        }
    }
}
