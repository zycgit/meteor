/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain.warp;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfResponse;

/**
 * 调用请求
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractRsfResponseWarp implements RsfResponse {
    protected abstract RsfResponse getRsfResponse();

    @Override
    public RsfBindInfo<?> getBindInfo() {
        return this.getRsfResponse().getBindInfo();
    }

    @Override
    public long getRequestID() {
        return this.getRsfResponse().getRequestID();
    }

    @Override
    public String getSerializeType() {
        return this.getRsfResponse().getSerializeType();
    }

    @Override
    public String[] getOptionKeys() {
        return this.getRsfResponse().getOptionKeys();
    }

    @Override
    public String getOption(String key) {
        return this.getRsfResponse().getOption(key);
    }

    @Override
    public void addOption(String key, String value) {
        this.getRsfResponse().addOption(key, value);
    }

    @Override
    public void removeOption(String key) {
        this.getRsfResponse().removeOption(key);
    }

    @Override
    public Object getData() {
        return this.getRsfResponse().getData();
    }

    @Override
    public Class<?> getReturnType() {
        return this.getRsfResponse().getReturnType();
    }

    @Override
    public short getStatus() {
        return this.getRsfResponse().getStatus();
    }

    @Override
    public void sendData(Object returnObject) {
        this.getRsfResponse().sendData(returnObject);
    }

    @Override
    public void sendStatus(short status) {
        this.getRsfResponse().sendStatus(status);
    }

    @Override
    public void sendStatus(short status, String messageBody) {
        this.getRsfResponse().sendStatus(status, messageBody);
    }

    @Override
    public boolean isResponse() {
        return this.getRsfResponse().isResponse();
    }
}