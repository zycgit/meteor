/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain.warp;
import java.lang.reflect.Method;
import java.util.Enumeration;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.address.InterAddress;

/**
 * RSF请求
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractRsfRequestWarp implements RsfRequest {
    protected abstract RsfRequest getRsfRequest();

    @Override
    public InterAddress getRemoteAddress() {
        return getRsfRequest().getRemoteAddress();
    }

    @Override
    public InterAddress getTargetAddress() {
        return getRsfRequest().getTargetAddress();
    }

    @Override
    public RsfBindInfo<?> getBindInfo() {
        return getRsfRequest().getBindInfo();
    }

    @Override
    public long getRequestID() {
        return getRsfRequest().getRequestID();
    }

    @Override
    public String getSerializeType() {
        return getRsfRequest().getSerializeType();
    }

    @Override
    public String[] getOptionKeys() {
        return getRsfRequest().getOptionKeys();
    }

    @Override
    public String getOption(String key) {
        return getRsfRequest().getOption(key);
    }

    @Override
    public void addOption(String key, String value) {
        getRsfRequest().addOption(key, value);
    }

    @Override
    public void removeOption(String key) {
        getRsfRequest().removeOption(key);
    }

    @Override
    public Object getAttribute(String attrKey) {
        return getRsfRequest().getAttribute(attrKey);
    }

    @Override
    public void setAttribute(String attrKey, Object attrValue) {
        getRsfRequest().setAttribute(attrKey, attrValue);
    }

    @Override
    public void removeAttribute(String attrKey) {
        getRsfRequest().removeAttribute(attrKey);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return getRsfRequest().getAttributeNames();
    }

    @Override
    public boolean isLocal() {
        return getRsfRequest().isLocal();
    }

    @Override
    public boolean isP2PCalls() {
        return getRsfRequest().isP2PCalls();
    }

    @Override
    public Method getMethod() {
        return getRsfRequest().getMethod();
    }

    @Override
    public RsfContext getContext() {
        return getRsfRequest().getContext();
    }

    @Override
    public long getReceiveTime() {
        return getRsfRequest().getReceiveTime();
    }

    @Override
    public int getTimeout() {
        return getRsfRequest().getTimeout();
    }

    @Override
    public Class<?>[] getParameterTypes() {
        return getRsfRequest().getParameterTypes();
    }

    @Override
    public Object[] getParameterObject() {
        return getRsfRequest().getParameterObject();
    }

    @Override
    public boolean isMessage() {
        return getRsfRequest().isMessage();
    }
}