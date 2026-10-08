/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServiceData extends ServiceInfoData {
    private String              type             = null;
    private String              role             = null;
    private String[]            protocols        = new String[0];
    private Map<String, String> aliases          = new LinkedHashMap<>();
    private String[]            methods          = new String[0];
    private int                 timeout          = 0;
    private String              serializeType    = null;
    private boolean             message          = false;
    private boolean             sharedThreadPool = false;

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public Map<String, String> getAliases() {
        return this.aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases;
    }

    public String[] getMethods() {
        return this.methods;
    }

    public void setMethods(String[] methods) {
        this.methods = methods;
    }

    public int getTimeout() {
        return this.timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getSerializeType() {
        return this.serializeType;
    }

    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    public boolean isMessage() {
        return this.message;
    }

    public void setMessage(boolean message) {
        this.message = message;
    }

    public boolean isSharedThreadPool() {
        return this.sharedThreadPool;
    }

    public void setSharedThreadPool(boolean sharedThreadPool) {
        this.sharedThreadPool = sharedThreadPool;
    }
}
