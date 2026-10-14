/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.meteor.utils.ContextPathUtils;

/** 协议配置中的只读目录挂载表，按最长路径段匹配。 */
public final class ContextPaths {
    private final Map<String, Mount> mounts;
    private final List<Mount>        prefixes;
    private final List<Mount>        entries;
    private final List<String>       paths;

    public ContextPaths(Map<String, String> options) {
        Map<String, Mount> mounts = new LinkedHashMap<>();
        List<Mount> prefixes = new ArrayList<>();
        for (String path : ContextPathUtils.contextPaths(options)) {
            Mount mount = new Mount(path, false);
            mounts.put(path, mount);
            prefixes.add(mount);
        }

        prefixes.sort(Comparator.comparingInt((Mount mount) -> mount.path().length()).reversed());
        this.mounts = Collections.unmodifiableMap(mounts);
        this.prefixes = Collections.unmodifiableList(prefixes);
        this.entries = List.copyOf(mounts.values());
        this.paths = List.copyOf(mounts.keySet());
    }

    /** 服务声明的完整路径；尾部斜杠参与精确匹配。 */
    public static Mount exact(String path) {
        return new Mount(ContextPathUtils.exactPath(path), true);
    }

    /** 全部挂载按声明顺序返回，默认挂载在首位。 */
    public List<Mount> mounts() {
        return this.entries;
    }

    /** 全部挂载路径的只读视图。 */
    public List<String> paths() {
        return this.paths;
    }

    /** 点查已声明的挂载。 */
    public Mount get(String path) {
        return this.mounts.get(path);
    }

    /** 点查目录挂载，兼容选择时带有尾部斜杠的形式。 */
    public Mount resolve(String path) {
        Mount mount = this.mounts.get(path);
        if (mount != null || path == null) {
            return mount;
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        if (end == path.length()) {
            return null;
        }
        return this.mounts.get(path.substring(0, end));
    }

    /** 匹配最长目录前缀，查询参数不参与路径比较。 */
    public Mount match(String uri) {
        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        for (Mount mount : this.prefixes) {
            if (mount.matches(uri, end)) {
                return mount;
            }
        }
        return null;
    }

    /** 已编译的单个挂载；实例随配置共享，不为每次请求重新创建。 */
    public static final class Mount {
        private final String  path;
        private final boolean exact;
        private final String  prefix;

        private Mount(String path, boolean exact) {
            this.path = path;
            this.exact = exact;
            this.prefix = exact || path.length() == 1 ? "/" : path + "/";
        }

        public String path() {
            return this.path;
        }

        public boolean exact() {
            return this.exact;
        }

        /** 目录前缀含末尾斜杠；完整地址由协议从根路径解析。 */
        public String prefix() {
            return this.prefix;
        }

        /** 使用 URI 中的路径结束位置进行比较，不创建临时前缀或子串。 */
        public boolean matches(String uri, int end) {
            int length = this.path.length();
            if (end < length || !uri.startsWith(this.path)) {
                return false;
            }
            if (this.exact) {
                return end == length;
            }
            return end == length || length == 1 || uri.charAt(length) == '/';
        }
    }
}
