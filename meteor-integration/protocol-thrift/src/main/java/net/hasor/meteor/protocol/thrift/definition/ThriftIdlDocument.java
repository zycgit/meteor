/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.definition;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import net.hasor.cobble.StringUtils;

/** 一个 IDL 文档及其导入图，保留原文和语言命名空间。 */
public final class ThriftIdlDocument {
    public final URL                            source;
    public final String                         text;
    final        Map<String, String>            includePaths  = new LinkedHashMap<>();
    public final Map<String, ThriftIdlDocument> includes      = new LinkedHashMap<>();
    public final Map<String, String>            namespaces    = new LinkedHashMap<>();
    public final Map<String, ThriftIdlType>     types         = new LinkedHashMap<>();
    public final Map<String, ThriftIdlType>     constantTypes = new LinkedHashMap<>();
    public final Map<String, Object>            constants     = new LinkedHashMap<>();

    public ThriftIdlDocument(URL source, String text) {
        this.source = source;
        this.text = text;
    }

    /** 文件入口原样导出整个 include 图，保留相对文件路径。 */
    public Map<String, String> getSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        String path = this.source.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        this.collectSources(Path.of(name), sources, new HashSet<>());
        return Collections.unmodifiableMap(sources);
    }

    private void collectSources(Path path, Map<String, String> sources, Set<String> visited) {
        if (!visited.add(this.source.toExternalForm())) {
            return;
        }
        String previous = sources.putIfAbsent(path.toString().replace('\\', '/'), this.text);
        if (previous != null && !StringUtils.equals(previous, this.text)) {
            throw new IllegalArgumentException("Thrift resource path collision: " + path);
        }
        Path parent = path.getParent();
        if (parent == null) {
            parent = Path.of("");
        }
        for (Map.Entry<String, ThriftIdlDocument> include : this.includes.entrySet()) {
            Path included = parent.resolve(this.includePaths.get(include.getKey())).normalize();
            include.getValue().collectSources(included, sources, visited);
        }
    }

    /** 精确解析符号，不按相似名称或文件顺序选择类型。 */
    public ThriftIdlType resolve(ThriftIdlType type) {
        if (!StringUtils.equals(type.kind, "reference")) {
            return type;
        }
        String name = type.name;
        int separator = name.indexOf('.');
        if (separator >= 0) {
            ThriftIdlDocument imported = this.includes.get(name.substring(0, separator));
            if (imported == null) {
                throw new IllegalArgumentException(this.source + ": unknown include " + name);
            }
            return imported.resolve(new ThriftIdlType("reference", name.substring(separator + 1)));
        }
        ThriftIdlType resolved = this.types.get(name);
        if (resolved == null) {
            throw new IllegalArgumentException(this.source + ": unknown type " + name);
        }
        return resolved;
    }
}
