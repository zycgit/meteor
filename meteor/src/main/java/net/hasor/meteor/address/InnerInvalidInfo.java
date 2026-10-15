/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.concurrent.TimeUnit;

/**
 * 失效信息
 * @version : 2015年10月3日
 * @author 赵永春 (zyc@hasor.net)
 */
class InnerInvalidInfo {
    private long timeoutNanos;
    private int  tryCount;

    public InnerInvalidInfo(long timeoutMs) {
        this.timeoutNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        this.tryCount = 0;
    }

    public void invalid(long timeoutMs) {
        long now = System.nanoTime();
        if (now - this.timeoutNanos < 0) {
            this.tryCount++;
        }
        this.timeoutNanos = now + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    public boolean reTry() {
        if (System.nanoTime() - this.timeoutNanos < 0) {
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