/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.math.BigDecimal;
import net.hasor.meteor.protocol.soap.annotation.MetXmlAttribute;
import net.hasor.meteor.protocol.soap.annotation.MetXmlType;
import net.hasor.meteor.protocol.soap.annotation.MetXmlValue;

/** XML simpleContent：金额保存为文本，币种保存为属性。 */
@MetXmlType(name = "Amount", namespace = MetXmlOrder.TYPES, propOrder = { "value" })
public class MetXmlAmount {
    @MetXmlValue
    private BigDecimal value;
    @MetXmlAttribute(name = "currency", required = true)
    private String     currency;

    public BigDecimal getValue() {
        return this.value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getCurrency() {
        return this.currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
