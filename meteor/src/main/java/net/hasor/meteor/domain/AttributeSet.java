/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import net.hasor.cobble.CollectionUtils;

/**
 *
 * @version : 2015年1月24日
 * @author 赵永春 (zyc@hasor.net)
 */
public class AttributeSet extends OptionInfo {
    private final Map<String, Object> attributeMap = new HashMap<>();

    /**获取属性*/
    public Object getAttribute(String attrKey) {
        return this.attributeMap.get(attrKey);
    }

    /**保存属性,属性会在请求完毕之后丢失。特性和 web 下的 request 属性类似。*/
    public void setAttribute(String attrKey, Object attrValue) {
        this.attributeMap.put(attrKey, attrValue);
    }

    /**删除属性*/
    public void removeAttribute(String attrKey) {
        this.attributeMap.remove(attrKey);
    }

    /**获取所有属性名。*/
    public Enumeration<String> getAttributeNames() {
        return CollectionUtils.asEnumeration(new ArrayList<>(this.attributeMap.keySet()).iterator());
    }
}