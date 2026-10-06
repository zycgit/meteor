/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
/**
 * 地址类型
 * @version : 2015年10月20日
 * @author 赵永春 (zyc@hasor.net)
 */
public enum AddressTypeEnum {
    Dynamic(1, "dynamic", "D|"),
    Static(2, "static", "S|"),
    ;
    private final int    type;
    private final String desc;
    private final String shortType;

    AddressTypeEnum(int type, String desc, String shortType) {
        this.type = type;
        this.desc = desc;
        this.shortType = shortType;
    }

    public String getDesc() {
        return this.desc;
    }

    public String getShortType() {
        return this.shortType;
    }

    @Override
    public String toString() {
        return "Enum[type = " + this.type + " , desc = " + this.desc + "]";
    }
}