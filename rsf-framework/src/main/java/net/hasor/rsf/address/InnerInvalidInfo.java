/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
/**
 * 失效信息
 * @version : 2015年10月3日
 * @author 赵永春 (zyc@hasor.net)
 */
class InnerInvalidInfo {
    private long timeoutPoint;
    private int  tryCount;

    public InnerInvalidInfo(long timeoutMs) {
        this.timeoutPoint = System.currentTimeMillis() + timeoutMs;
        this.tryCount = 0;
    }

    public void invalid(long timeoutMs) {
        if (this.timeoutPoint > System.currentTimeMillis()) {
            this.tryCount++;
        }
        this.timeoutPoint = System.currentTimeMillis() + timeoutMs;
    }

    public boolean reTry() {
        if (this.timeoutPoint > System.currentTimeMillis()) {
            return false;
        }
        this.tryCount = 0;
        return true;
    }

    @Override
    public String toString() {
        return "InvalidInfo[tryCount = " + this.tryCount + " ]";
    }
}