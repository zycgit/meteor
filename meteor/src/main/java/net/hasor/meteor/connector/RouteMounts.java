/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.meteor.utils.ContextPathUtils;

/** Immutable protocol context mounts used while routing requests. */
public final class RouteMounts {
    private final Map<String, RouteMount> mounts;
    private final List<RouteMount>        prefixes;
    private final List<RouteMount>        entries;
    private final List<String>            paths;

    RouteMounts(Map<String, String> options) {
        Map<String, RouteMount> mounts = new LinkedHashMap<>();
        List<RouteMount> prefixes = new ArrayList<>();
        for (String path : ContextPathUtils.contextPaths(options)) {
            RouteMount mount = new RouteMount(path, false);
            mounts.put(path, mount);
            prefixes.add(mount);
        }

        prefixes.sort(Comparator.comparingInt((RouteMount mount) -> mount.path().length()).reversed());
        this.mounts = Collections.unmodifiableMap(mounts);
        this.prefixes = Collections.unmodifiableList(prefixes);
        this.entries = List.copyOf(mounts.values());
        this.paths = List.copyOf(mounts.keySet());
    }

    /** Return all mounts in declaration order, with the default mount first. */
    public List<RouteMount> mounts() {
        return this.entries;
    }

    /** Return the immutable declared path view. */
    public List<String> paths() {
        return this.paths;
    }

    /** Find a declared mount by its normalized path. */
    public RouteMount get(String path) {
        return this.mounts.get(path);
    }

    /** Resolve a directory mount while accepting a trailing slash in the selection. */
    public RouteMount resolve(String path) {
        RouteMount mount = this.mounts.get(path);
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

    /** Match the longest directory prefix; query parameters do not participate. */
    public RouteMount match(String uri) {
        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        for (RouteMount mount : this.prefixes) {
            if (mount.matches(uri, end)) {
                return mount;
            }
        }
        return null;
    }
}
