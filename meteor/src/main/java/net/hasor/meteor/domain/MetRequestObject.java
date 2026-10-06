/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.lang.reflect.Method;
import java.util.Enumeration;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.address.InterAddress;

/** One RPC invocation, with its direction and metadata supplied by the creator. */
public class MetRequestObject implements MetRequest {
    private final long           requestID;
    private final boolean        local;
    private final MetContext     context;
    private final MetBindInfo<?> bindInfo;
    private final Method         targetMethod;
    private final Class<?>[]     parameterTypes;
    private final Object[]       parameterObjects;
    private final AttributeSet   attributes = new AttributeSet();
    private       MetOptionSet   options    = this.attributes;
    private       InterAddress   peerAddress;
    private       short          flags;
    private       String         serializeType;
    private       boolean        message;
    private       int            timeout;
    private       long           receiveTime;

    public MetRequestObject(long requestID, boolean local, MetContext context, MetBindInfo<?> bindInfo, Method targetMethod, Object[] parameterObjects) {
        this.requestID = requestID;
        this.local = local;
        this.context = context;
        this.bindInfo = bindInfo;
        this.targetMethod = targetMethod;
        this.parameterTypes = targetMethod.getParameterTypes();
        this.parameterObjects = parameterObjects;
    }

    @Override
    public long getRequestID() {
        return this.requestID;
    }

    @Override
    public boolean isLocal() {
        return this.local;
    }

    @Override
    public MetContext getContext() {
        return this.context;
    }

    @Override
    public MetBindInfo<?> getBindInfo() {
        return this.bindInfo;
    }

    @Override
    public Method getMethod() {
        return this.targetMethod;
    }

    @Override
    public Class<?>[] getParameterTypes() {
        return this.parameterTypes.clone();
    }

    @Override
    public Object[] getParameterObject() {
        return this.parameterObjects == null ? new Object[0] : this.parameterObjects.clone();
    }

    public short getFlags() {
        return this.flags;
    }

    public void setFlags(short flags) {
        this.flags = flags;
    }

    @Override
    public boolean isP2PCalls() {
        return MetFlags.P2PFlag.testTag(this.flags);
    }

    @Override
    public String getSerializeType() {
        return this.serializeType;
    }

    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    @Override
    public boolean isMessage() {
        return this.message;
    }

    public void setMessage(boolean message) {
        this.message = message;
    }

    @Override
    public int getTimeout() {
        return this.timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public long getReceiveTime() {
        // MetRequest defines current time for outbound calls and arrival time for inbound calls.
        return this.local ? System.currentTimeMillis() : this.receiveTime;
    }

    public void setReceiveTime(long receiveTime) {
        this.receiveTime = receiveTime;
    }

    public void setPeerAddress(InterAddress peerAddress) {
        this.peerAddress = peerAddress;
    }

    @Override
    public InterAddress getRemoteAddress() {
        // Preserve the existing API: outbound requests report the local RSF address here.
        return this.local ? this.getBindAddress() : this.peerAddress;
    }

    @Override
    public InterAddress getTargetAddress() {
        return this.local ? this.peerAddress : this.getBindAddress();
    }

    private InterAddress getBindAddress() {
        String protocol;
        if (this.peerAddress == null) {
            protocol = this.context.getSettings().getBindAddressSet(this.context.getDefaultProtocol()).getSchema();
        } else {
            protocol = this.peerAddress.getSchema();
        }
        return this.context.bindAddress(protocol);
    }

    /** Inbound calls share the payload's options; attributes always remain request-local. */
    public void setOptions(MetOptionSet options) {
        this.options = options;
    }

    @Override
    public String[] getOptionKeys() {
        return this.options.getOptionKeys();
    }

    @Override
    public String getOption(String key) {
        return this.options.getOption(key);
    }

    @Override
    public void addOption(String key, String value) {
        this.options.addOption(key, value);
    }

    @Override
    public void removeOption(String key) {
        this.options.removeOption(key);
    }

    public void addOptionMap(MetOptionSet options) {
        if (options == null) {
            return;
        }
        for (String key : options.getOptionKeys()) {
            this.addOption(key, options.getOption(key));
        }
    }

    @Override
    public Object getAttribute(String key) {
        return this.attributes.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        this.attributes.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        this.attributes.removeAttribute(key);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return this.attributes.getAttributeNames();
    }

    @Override
    public String toString() {
        return "requestID:" + this.requestID + (this.local ? " from Local," : " from Remote,") + this.bindInfo;
    }
}
