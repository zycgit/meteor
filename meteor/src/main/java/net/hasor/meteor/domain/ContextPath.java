/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.*;
import net.hasor.meteor.utils.ContextPathUtils;

/** 服务在一个协议下的路径声明；所有路径使用相同的匹配方式。 */
public final class ContextPath {
    private final Set<String> paths;
    private final boolean     exact;

    public ContextPath(Collection<String> paths, boolean exact) {
        Objects.requireNonNull(paths, "paths");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Paths must not be empty");
        }

        // 前缀统一去掉尾斜杠；精确地址保留原始形式，声明顺序用于出站选择。
        Set<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            if (exact) {
                normalized.add(ContextPathUtils.exactPath(path));
            } else {
                normalized.add(ContextPathUtils.normalize(path));
            }
        }
        this.paths = Collections.unmodifiableSet(normalized);
        this.exact = exact;
    }

    /** 按声明顺序返回只读路径集合。 */
    public Set<String> getPaths() {
        return this.paths;
    }

    /** true 为精确匹配，false 为目录前缀匹配。 */
    public boolean isExact() {
        return this.exact;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ContextPath other)) {
            return false;
        }
        return this.exact == other.exact && this.paths.equals(other.paths);
    }

    @Override
    public int hashCode() {
        return 31 * this.paths.hashCode() + Boolean.hashCode(this.exact);
    }
}
