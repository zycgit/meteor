/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** XML simpleContent：金额保存为文本，币种保存为属性。 */
@MetType(name = "Amount")
@JacksonXmlRootElement(namespace = MetXmlOrder.TYPES)
public class MetXmlAmount {
    @JacksonXmlText
    private BigDecimal value;
    @MetField(name = "currency")
    @JacksonXmlProperty(isAttribute = true)
    @JsonProperty(required = true)
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
