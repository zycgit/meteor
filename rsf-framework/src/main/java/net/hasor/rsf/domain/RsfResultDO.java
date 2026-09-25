/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
import net.hasor.rsf.RsfResult;

/**
 * 消息调用结果集
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfResultDO implements RsfResult {
    private static final long    serialVersionUID = -4678893554960623786L;
    private              long    messageID;
    private              boolean success;
    private              int     errorCode        = 0;
    private              String  errorMessage     = "";

    public RsfResultDO() {
    }

    public RsfResultDO(long messageID, boolean success) {
        this.messageID = messageID;
        this.success = success;
    }

    @Override
    public boolean isSuccess() {
        return this.success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public long getMessageID() {
        return this.messageID;
    }

    public void setMessageID(long messageID) {
        this.messageID = messageID;
    }

    @Override
    public int getErrorCode() {
        return this.errorCode;
    }

    @Override
    public String getErrorMessage() {
        return this.errorMessage;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}