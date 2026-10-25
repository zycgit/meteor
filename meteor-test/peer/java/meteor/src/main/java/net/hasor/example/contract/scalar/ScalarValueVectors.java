/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON 仅保存语言无关的输入向量；RPC 参数仍使用各协议的真实声明类型。 */
public final class ScalarValueVectors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ScalarValueVectors() {
    }

    public static List<String> names(String family) throws IOException {
        List<String> names = new ArrayList<>();
        table(family).fieldNames().forEachRemaining(names::add);
        return names;
    }

    public static ScalarValue value(String name) throws IOException {
        return JSON.treeToValue(table("values").required(name), ScalarValue.class);
    }

    public static ScalarMoment moment(String name) throws IOException {
        String instant = table("moments").required(name).textValue();
        ScalarMoment value = new ScalarMoment();
        value.setInstant(Date.from(Instant.parse(instant)));
        value.setReceipt("client-instant");
        return value;
    }

    public static ScalarDecimal decimal(String name) throws IOException {
        String amount = table("decimals").required(name).textValue();
        ScalarDecimal value = new ScalarDecimal();
        value.setAmount(new BigDecimal(amount));
        value.setReceipt("client-amount");
        return value;
    }

    private static JsonNode table(String family) throws IOException {
        try (InputStream input = ScalarValueVectors.class.getResourceAsStream("/vectors/scalar/values.json")) {
            if (input == null) {
                throw new IOException("Missing shared scalar vectors");
            }
            return JSON.readTree(input).required(family);
        }
    }
}
