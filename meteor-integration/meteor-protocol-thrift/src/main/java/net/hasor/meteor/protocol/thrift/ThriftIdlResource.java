/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.protocol.thrift.codec.ThriftServiceSchema;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlDocument;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlParser;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlType;

/** 本地 Thrift IDL 契约；文件解析不创建服务实现、DTO 或网络连接。 */
public final class ThriftIdlResource {
    private final ThriftIdlDocument document;
    private       String            serviceName;

    public ThriftIdlResource(URL source) throws IOException {
        this.document = ThriftIdlParser.load(source);
    }

    /** 多服务文档显式选择服务符号，不修改文件中的服务名称。 */
    public ThriftIdlResource selectService(String name) {
        this.serviceName = name;
        return this;
    }

    public ThriftIdlDocument getDocument() {
        return this.document;
    }

    public Map<String, String> getSources() {
        return this.document.getSources();
    }

    public ThriftServiceSchema bind(MetBindInfo<?> service) {
        String selected = this.serviceName;
        if (selected == null) {
            selected = service.getAliasName("thrift");
        }
        if (selected != null) {
            ThriftIdlType type = this.document.types.get(selected);
            if (type == null || !StringUtils.equals(type.kind, "service")) {
                throw new IllegalArgumentException(this.document.source + ": unknown Thrift service " + selected);
            }
            return ThriftServiceSchema.idl(service, type);
        }

        // 没有选择器时只接受唯一服务，不按文档声明顺序猜测。
        List<ThriftIdlType> candidates = new ArrayList<>();
        for (ThriftIdlType type : this.document.types.values()) {
            if (StringUtils.equals(type.kind, "service")) {
                candidates.add(type);
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(this.document.source + ": select one Thrift service explicitly");
        }
        return ThriftServiceSchema.idl(service, candidates.get(0));
    }
}
