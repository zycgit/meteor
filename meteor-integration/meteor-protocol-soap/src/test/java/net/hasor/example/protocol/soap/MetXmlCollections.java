/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** 对比集合容器、重复数组元素和重复枚举元素的直接 XML 表达。 */
@MetType(name = "Collections")
@JacksonXmlRootElement(namespace = MetXmlOrder.TYPES)
public class MetXmlCollections {
    @MetField(name = "item")
    @JacksonXmlElementWrapper(localName = "items")
    @JsonProperty(index = 10)
    private List<String>      wrapped;
    @MetField(name = "number")
    @JsonProperty(index = 20)
    private Integer[]         unwrapped;
    @MetField(name = "state")
    @JsonProperty(index = 30)
    private List<MetXmlState> states;

    public List<String> getWrapped() {
        return this.wrapped;
    }

    public void setWrapped(List<String> wrapped) {
        this.wrapped = wrapped;
    }

    public Integer[] getUnwrapped() {
        return this.unwrapped;
    }

    public void setUnwrapped(Integer[] unwrapped) {
        this.unwrapped = unwrapped;
    }

    public List<MetXmlState> getStates() {
        return this.states;
    }

    public void setStates(List<MetXmlState> states) {
        this.states = states;
    }
}
