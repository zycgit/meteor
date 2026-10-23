/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import javax.xml.XMLConstants;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** 公共注解声明名称，Jackson XML 声明属性、命名空间和集合容器。 */
@MetType(name = "Order")
@JacksonXmlRootElement(namespace = MetXmlOrder.TYPES)
public class MetXmlOrder {
    public static final String TYPES      = "urn:meteor:xml:types";
    public static final String BUYER      = "urn:meteor:xml:buyer";
    public static final String DELIVERY   = "urn:meteor:xml:delivery";
    public static final String ATTRIBUTES = "urn:meteor:xml:attributes";

    @MetField(name = "id")
    @JacksonXmlProperty(isAttribute = true, namespace = ATTRIBUTES)
    @JsonProperty(index = 0, required = true)
    private String       id;
    @MetField(name = "lang")
    @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.XML_NS_URI)
    @JsonProperty(index = 1)
    private String       language;
    @MetField(name = "name")
    @JacksonXmlProperty(namespace = BUYER)
    @JsonProperty(index = 10, required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    private String       buyer;
    @MetField(name = "name")
    @JacksonXmlProperty(namespace = DELIVERY)
    @JsonProperty(index = 20, required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    private String       delivery;
    @MetField(name = "line")
    @JacksonXmlProperty(namespace = TYPES)
    @JacksonXmlElementWrapper(localName = "lines", namespace = TYPES)
    @JsonProperty(index = 30)
    private List<String> lines;
    @MetField(name = "status")
    @JacksonXmlProperty(namespace = TYPES)
    @JsonProperty(index = 40)
    private MetXmlState  state;
    @MetField(name = "note")
    @JsonProperty(index = 50)
    private String       memo;
    @JsonIgnore
    private String       internalToken;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLanguage() {
        return this.language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getBuyer() {
        return this.buyer;
    }

    public void setBuyer(String buyer) {
        this.buyer = buyer;
    }

    public String getDelivery() {
        return this.delivery;
    }

    public void setDelivery(String delivery) {
        this.delivery = delivery;
    }

    public List<String> getLines() {
        return this.lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }

    public MetXmlState getState() {
        return this.state;
    }

    public void setState(MetXmlState state) {
        this.state = state;
    }

    public String getMemo() {
        return this.memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public String getInternalToken() {
        return this.internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
