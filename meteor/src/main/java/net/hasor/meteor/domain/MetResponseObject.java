/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;

/**
 * 调用请求
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class MetResponseObject extends OptionInfo implements MetResponse {
    private final MetRequest rsfRequest;
    private       short      status       = ProtocolStatus.Unknown;
    private       Object     returnObject = null;
    private       boolean    committed    = false;

    public MetResponseObject(MetRequest rsfRequest) {
        this.rsfRequest = rsfRequest;
    }

    @Override
    public String toString() {
        return "responseID:" + this.getRequestID() + " from Setvice " + this.getBindInfo();
    }

    @Override
    public MetBindInfo<?> getBindInfo() {
        return this.rsfRequest.getBindInfo();
    }

    @Override
    public long getRequestID() {
        return this.rsfRequest.getRequestID();
    }

    @Override
    public String getSerializeType() {
        return this.rsfRequest.getSerializeType();
    }

    @Override
    public Object getData() {
        return this.returnObject;
    }

    @Override
    public Class<?> getReturnType() {
        return this.rsfRequest.getMethod().getReturnType();
    }

    @Override
    public short getStatus() {
        return this.status;
    }

    @Override
    public void sendData(Object returnObject) {
        this.updateReturn(ProtocolStatus.OK, returnObject, null);
    }

    @Override
    public void sendStatus(short status) {
        this.updateReturn(status, null, null);
    }

    @Override
    public void sendStatus(short status, String returnMessage) {
        this.updateReturn(status, null, returnMessage);
    }

    private void updateReturn(short status, Object returnData, String returnMessage) {
        this.status = status;
        this.returnObject = returnData;
        this.committed = true;
        if (StringUtils.isNotBlank(returnMessage)) {
            this.addOption("message", returnMessage);
        }
    }

    @Override
    public boolean isResponse() {
        return this.committed;
    }
}