/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.state;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.composition.state.OrderCompositionConfiguration;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 注解协议或 ONC RPC 替换已有订单后，RSF 读取同一业务存储中的最新字段。 */
@NxnRpc(name = "rsf", version = "1")
@NxnRpc(name = "thrift", cases = "replace-through=thrift")
@NxnRpc(name = "oncrpc", version = "2", cases = "replace-through=oncrpc")
@ParameterizedClass(autoCloseArguments = false, name = "replace-through={0}", quoteTextArguments = false)
@MethodSource("protocols")
public class OrderReplacementTest {
    private final OrderProtocol writer;

    public OrderReplacementTest(OrderProtocol writer) {
        this.writer = writer;
    }
    public static Collection<Object[]> protocols() {
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { OrderProtocol.THRIFT });
        protocols.add(new Object[] { OrderProtocol.ONCRPC });
        return protocols;
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void replacementIsVisibleThroughOrdinaryRpc() throws Exception {
        // 只启用普通 RSF 和当前写入协议，两个协议共用一个业务对象。
        MetConfiguration incoming = OrderCompositionConfiguration.provider(OrderProtocol.RSF, this.writer, false);
        MetConfiguration outgoing = OrderProtocol.RSF.consumerConfiguration();
        this.writer.mount(outgoing.connector("tcp"));
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            OrderDemoServiceImpl implementation = new OrderDemoServiceImpl();
            OrderCompositionConfiguration.publish(provider, implementation, OrderProtocol.RSF, this.writer);
            MetBindInfo<?> ordinaryBinding;
            MetBindInfo<?> writerBinding;
            if (this.writer == OrderProtocol.ONCRPC) {
                ordinaryBinding = OrderProtocol.RSF.subscribe(consumer);
                writerBinding = this.writer.subscribe(consumer);
            } else {
                // Thrift 与 RSF 已共用服务身份，在同一个订阅中累积两个协议。
                var subscription = consumer.publisher().rsfService(OrderDemoService.class);
                OrderProtocol.RSF.configure(subscription);
                this.writer.configure(subscription);
                ordinaryBinding = subscription.register();
                writerBinding = ordinaryBinding;
            }
            MetClient ordinary = consumer.getRsfClient(provider.getListenAddress("rsf"));
            MetClient replacement = consumer.getRsfClient(provider.getListenAddress(this.writer.protocolName()));

            // 普通接口先保存，再由当前写入协议按相同 ID 完整替换描述和附件。
            Order initial = OrderMatrixVectors.order("order");
            OrderProtocol.RSF.save(ordinary, ordinaryBinding, initial);
            Order updated = OrderMatrixVectors.order("order");
            updated.setDescription("replacement through " + this.writer.protocolName());
            updated.setAttachment(new byte[] { 10, 11, 12 });
            this.writer.save(replacement, writerBinding, updated);

            // 读取者必须观察替换后的值，不能返回先前保存的旧对象。
            Order actual = OrderProtocol.RSF.find(ordinary, ordinaryBinding, initial.getId());
            assertEquals(updated.getId(), actual.getId());
            assertEquals(updated.getDescription(), actual.getDescription());
            assertArrayEquals(updated.getAttachment(), actual.getAttachment());
        }
    }
}
