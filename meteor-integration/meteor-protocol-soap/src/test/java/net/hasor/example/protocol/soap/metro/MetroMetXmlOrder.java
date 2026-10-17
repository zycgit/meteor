/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.metro;
import java.util.List;
import javax.xml.XMLConstants;
import jakarta.xml.bind.annotation.*;

/** 标准 Metro 对端自己的 JAXB DTO，不使用 Meteor 映射注解。 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "Order", namespace = MetroMetXmlOrder.TYPES, propOrder = { "buyer", "delivery", "lines", "state", "memo" })
public class MetroMetXmlOrder {
    public static final String TYPES      = "urn:meteor:xml:types";
    public static final String BUYER      = "urn:meteor:xml:buyer";
    public static final String DELIVERY   = "urn:meteor:xml:delivery";
    public static final String ATTRIBUTES = "urn:meteor:xml:attributes";

    @XmlAttribute(name = "id", namespace = MetroMetXmlOrder.ATTRIBUTES, required = true)
    private String       id;
    @XmlAttribute(name = "lang", namespace = XMLConstants.XML_NS_URI)
    private String       language;
    @XmlElement(name = "name", namespace = MetroMetXmlOrder.BUYER, required = true)
    private String       buyer;
    @XmlElement(name = "name", namespace = MetroMetXmlOrder.DELIVERY, required = true)
    private String       delivery;
    @XmlElementWrapper(name = "lines", namespace = MetroMetXmlOrder.TYPES, nillable = true)
    @XmlElement(name = "line", namespace = MetroMetXmlOrder.TYPES, nillable = true)
    private List<String> lines;
    @XmlElement(name = "status", namespace = MetroMetXmlOrder.TYPES, nillable = true)
    private String       state;
    @XmlElement(name = "note", namespace = "", nillable = true)
    private String       memo;

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

    public String getState() {
        return this.state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMemo() {
        return this.memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
