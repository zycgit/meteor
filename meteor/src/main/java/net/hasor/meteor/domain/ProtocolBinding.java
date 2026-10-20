/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.Map;

/** 一个协议在指定连接器上接受服务后准备的发布结果。 */
public final class ProtocolBinding {
    private final String              scheme;
    private final Map<String, String> options;
    private final ContextPath         contextPath;
    private final Object              definition;

    public ProtocolBinding(String scheme, Map<String, String> options, ContextPath contextPath, Object definition) {
        this.scheme = scheme;
        this.options = options;
        this.contextPath = contextPath;
        this.definition = definition;
    }

    public String getScheme() {
        return this.scheme;
    }

    public Map<String, String> getOptions() {
        return this.options;
    }

    /** 此端点实际采用的路径；非 HTTP 接入时可以为 null。 */
    public ContextPath getContextPath() {
        return this.contextPath;
    }

    /** 协议扩展准备的定义数据；不需要附带数据时为 null。 */
    public Object getDefinition() {
        return this.definition;
    }

    /** 补齐有效路径并返回副本，不改变协议已准备的定义数据。 */
    public ProtocolBinding withContextPaths(ContextPath contextPaths) {
        return new ProtocolBinding(this.scheme, this.options, contextPaths, this.definition);
    }
}
