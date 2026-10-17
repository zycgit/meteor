/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElementWrapper;
import net.hasor.meteor.protocol.soap.annotation.MetXmlType;

/** 对比集合容器、重复数组元素和重复枚举元素的直接 XML 表达。 */
@MetXmlType(name = "Collections", namespace = MetXmlOrder.TYPES, propOrder = { "wrapped", "unwrapped", "states" })
public class MetXmlCollections {
    @MetXmlElementWrapper(name = "items")
    @MetXmlElement(name = "item")
    private List<String>      wrapped;
    @MetXmlElement(name = "number")
    private Integer[]         unwrapped;
    @MetXmlElement(name = "state")
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
