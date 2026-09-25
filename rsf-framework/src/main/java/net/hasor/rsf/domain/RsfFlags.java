/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
/**
 * 请求头标记
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public enum RsfFlags {
    P2PFlag((short) 0),      // 第0位，P2P调用
    ;
    private final int flagMark;

    RsfFlags(short flagMark) {
        this.flagMark = flagMark;
    }

    public short addTag(short oldValue) {
        return (short) (1 << this.flagMark | oldValue);
    }

    public short removeTag(short oldValue) {
        return (short) (~(1 << this.flagMark) & oldValue);
    }

    public boolean testTag(short oldValue) {
        return addTag(oldValue) == oldValue;
    }
}