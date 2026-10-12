/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过真正的 RPC 选择序列化器，传递业务对象而不直接调用编解码实现。 */
public class SerializationScenarioTest {
    @Test
    public void useJavaJsonAndHessianForTheSameBusinessContract() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 同一接口按名称分别发布；序列化 SPI 名称允许不同大小写。
            for (String format : List.of("Java", "Json", "Hessian")) {
                var publication = server.publisher().rsfService(Orders.class, order -> order);
                publication.name(format).serialize(format.toLowerCase(Locale.ROOT)).register();
                var subscription = client.publisher().rsfService(Orders.class);
                subscription.name(format).serialize(format.toUpperCase(Locale.ROOT));
                MetBindInfo<Orders> service = subscription.register();
                Orders remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

                // 普通 POJO、二进制字段、Unicode 和 null 均穿过协议及序列化链路。
                Order order = new Order();
                order.setId(42);
                order.setDescription("订单🙂");
                order.setAttachment(new byte[] { 0, 1, -1 });
                Order result = remote.save(order);
                assertNotSame(order, result);
                assertEquals(format, 42, result.getId());
                assertEquals(format, "订单🙂", result.getDescription());
                assertArrayEquals(format, order.getAttachment(), result.getAttachment());
                assertNull(format, remote.save(null));
            }
        }
    }

    public interface Orders {
        Order save(Order order);
    }

    public static class Order implements Serializable {
        private static final long   serialVersionUID = 1L;
        private              int    id;
        private              String description;
        private              byte[] attachment;

        public int getId() {
            return this.id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getDescription() {
            return this.description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public byte[] getAttachment() {
            return this.attachment;
        }

        public void setAttachment(byte[] attachment) {
            this.attachment = attachment;
        }
    }
}
