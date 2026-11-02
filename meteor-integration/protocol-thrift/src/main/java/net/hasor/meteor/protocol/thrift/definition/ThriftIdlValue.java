/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.definition;
import java.util.*;
import net.hasor.cobble.StringUtils;

/** 区分字符串、常量引用及复合默认值，避免把标识符误当作文本。 */
public final class ThriftIdlValue {
    private final ThriftIdlDocument document;
    private final String            kind;
    private final Object            value;

    public ThriftIdlValue(ThriftIdlDocument document, String kind, Object value) {
        this.document = document;
        this.kind = kind;
        this.value = value;
    }

    /** 每次建立独立容器；解码后不同 DTO 不共享可变默认值。 */
    @SuppressWarnings("unchecked")
    public Object evaluate(Set<String> resolving) {
        if (StringUtils.equals(this.kind, "list")) {
            List<Object> values = new ArrayList<>();
            for (ThriftIdlValue member : (List<ThriftIdlValue>) this.value) {
                values.add(member.evaluate(resolving));
            }
            return values;
        }
        if (StringUtils.equals(this.kind, "map")) {
            Map<Object, Object> values = new LinkedHashMap<>();
            for (Map.Entry<ThriftIdlValue, ThriftIdlValue> entry : ((Map<ThriftIdlValue, ThriftIdlValue>) this.value).entrySet()) {
                values.put(entry.getKey().evaluate(resolving), entry.getValue().evaluate(resolving));
            }
            return values;
        }
        if (!StringUtils.equals(this.kind, "reference")) {
            return this.value;
        }

        // 引用保留声明文档，跨 include 的同名常量不会串用。
        String name = (String) this.value;
        String key = this.document.source + "#" + name;
        if (!resolving.add(key)) {
            throw new IllegalArgumentException("Cyclic Thrift constant: " + key);
        }
        try {
            Object constant = this.document.constants.get(name);
            if (constant instanceof ThriftIdlValue expression) {
                return expression.evaluate(resolving);
            }
            int separator = name.indexOf('.');
            if (separator >= 0) {
                String prefix = name.substring(0, separator);
                String suffix = name.substring(separator + 1);
                ThriftIdlDocument imported = this.document.includes.get(prefix);
                if (imported != null) {
                    return new ThriftIdlValue(imported, "reference", suffix).evaluate(resolving);
                }
                ThriftIdlType type = this.document.types.get(prefix);
                if (type != null && type.enumeration.containsKey(suffix)) {
                    return type.enumeration.get(suffix);
                }
            }
            throw new IllegalArgumentException(this.document.source + ": unknown constant " + name);
        } finally {
            resolving.remove(key);
        }
    }
}
