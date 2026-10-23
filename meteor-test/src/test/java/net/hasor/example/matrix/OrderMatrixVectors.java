/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.matrix;
import java.io.IOException;
import java.io.InputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.order.Order;

/** 各语言可读取的公共订单向量；字节数组以 0..255 的整数数组表示。 */
public final class OrderMatrixVectors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OrderMatrixVectors() {
    }

    public static Order order(String name) throws IOException {
        // 每次从共享向量创建新业务对象，调用或保存不会污染后续测试。
        try (InputStream input = OrderMatrixVectors.class.getResourceAsStream("/vectors/basic/orders.json")) {
            JsonNode vector = JSON.readTree(input).required(name);
            Order order = new Order();
            order.setId(vector.required("id").intValue());
            order.setDescription(vector.required("description").textValue());

            // JSON 没有原生 byte 类型，使用明确的无符号整数保持跨语言一致。
            JsonNode values = vector.required("attachment");
            byte[] attachment = new byte[values.size()];
            for (int index = 0; index < attachment.length; index++) {
                attachment[index] = (byte) values.get(index).intValue();
            }
            order.setAttachment(attachment);
            return order;
        }
    }
}
