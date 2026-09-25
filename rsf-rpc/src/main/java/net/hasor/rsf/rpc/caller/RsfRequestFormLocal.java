/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.domain.AttributeSet;
import net.hasor.rsf.domain.RsfFlags;
import net.hasor.rsf.domain.RsfRuntimeUtils;

import java.lang.reflect.Method;

/**
 * RSF请求(本地发起调用)
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
class RsfRequestFormLocal extends AttributeSet implements RsfRequest {
    private final RsfCaller      rsfCaller;
    private final InterAddress   targetAddress;
    private final short          flags;
    private final long           requestID;
    private final RsfBindInfo<?> bindInfo;
    private final Method         targetMethod;
    private final Class<?>[]     parameterTypes;
    private final Object[]       parameterObjects;

    public RsfRequestFormLocal(InterAddress targetAddress, short flags, RsfBindInfo<?> bindInfo,//
            Method targetMethod, Object[] parameterObjects, RsfCaller rsfCaller) {
        this.requestID = RsfRuntimeUtils.genRequestID();
        this.targetAddress = targetAddress;
        this.flags = flags;
        this.bindInfo = bindInfo;
        this.targetMethod = targetMethod;
        this.parameterTypes = targetMethod.getParameterTypes();
        this.parameterObjects = parameterObjects;
        this.rsfCaller = rsfCaller;
    }

    @Override
    public String toString() {
        return "requestID:" + this.getRequestID() + " from Local," + this.bindInfo.toString();
    }

    @Override
    public boolean isP2PCalls() {
        return RsfFlags.P2PFlag.testTag(this.flags);
    }

    @Override
    public RsfBindInfo<?> getBindInfo() {
        return this.bindInfo;
    }

    @Override
    public long getRequestID() {
        return this.requestID;
    }

    @Override
    public String getSerializeType() {
        return this.bindInfo.getSerializeType();
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public boolean isMessage() {
        return this.bindInfo.isMessage();
    }

    @Override
    public Method getMethod() {
        return this.targetMethod;
    }

    @Override
    public RsfContext getContext() {
        return this.rsfCaller.getContext();
    }

    @Override
    public long getReceiveTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimeout() {
        return this.bindInfo.getClientTimeout();
    }

    @Override
    public Class<?>[] getParameterTypes() {
        return this.parameterTypes == null ? new Class[0] : this.parameterTypes.clone();
    }

    @Override
    public Object[] getParameterObject() {
        return this.parameterObjects == null ? new Object[0] : this.parameterObjects.clone();
    }

    @Override
    public InterAddress getRemoteAddress() {
        RsfContext rsfContext = this.rsfCaller.getContext();
        String protocol = null;
        if (this.getTargetAddress() == null) {
            String protocolName = rsfContext.getDefaultProtocol();
            protocol = rsfContext.getSettings().getBindAddressSet(protocolName).getSchema();
        } else {
            protocol = getTargetAddress().getSchema();
        }
        return rsfContext.bindAddress(protocol);
    }

    @Override
    public InterAddress getTargetAddress() {
        return this.targetAddress;
    }

    public short getFlags() {
        return this.flags;
    }
}