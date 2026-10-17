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
import net.hasor.meteor.protocol.soap.annotation.*;

/** 通过 Meteor 注解表达属性、命名空间、集合容器和枚举，无需 JAXB 注解。 */
@MetXmlType(name = "Order", namespace = MetXmlOrder.TYPES, //
        propOrder = { "buyer", "delivery", "lines", "state", "memo" })
public class MetXmlOrder {
    public static final String TYPES      = "urn:meteor:xml:types";
    public static final String BUYER      = "urn:meteor:xml:buyer";
    public static final String DELIVERY   = "urn:meteor:xml:delivery";
    public static final String ATTRIBUTES = "urn:meteor:xml:attributes";

    @MetXmlAttribute(name = "id", namespace = ATTRIBUTES, required = true)
    private String       id;
    @MetXmlAttribute(name = "lang", namespace = XMLConstants.XML_NS_URI)
    private String       language;
    @MetXmlElement(name = "name", namespace = BUYER, required = true, nillable = false)
    private String       buyer;
    @MetXmlElement(name = "name", namespace = DELIVERY, required = true, nillable = false)
    private String       delivery;
    @MetXmlElementWrapper(name = "lines", namespace = TYPES)
    @MetXmlElement(name = "line", namespace = TYPES)
    private List<String> lines;
    @MetXmlElement(name = "status", namespace = TYPES)
    private MetXmlState  state;
    @MetXmlElement(name = "note")
    private String       memo;
    @MetXmlTransient
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
