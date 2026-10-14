/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 目录字段的纯文本映射；各提供者自行执行读取、筛选与分页。 */
public final class DirectoryFilter {
    private DirectoryFilter() {
    }

    public static String text(Object record, String field, String fallback) {
        if (StringUtils.isBlank(field) || StringUtils.equals(field, "id") || StringUtils.equals(field, "instance")) {
            return fallback;
        }
        if (record instanceof InstanceStatus status) {
            record = status.getInstance();
        }

        EntInstanceCatalogData instance = null;
        EntServiceRuntimeData service = null;
        if (record instanceof RefServiceRelationData member) {
            instance = member.getInstance();
            service = member.getService();
        } else if (record instanceof EntInstanceCatalogData identity) {
            instance = identity;
        } else if (record instanceof EntServiceRuntimeData declaration) {
            service = declaration;
        }

        if (instance != null) {
            if (StringUtils.equals(field, "application")) {
                return StringUtils.defaultString(instance.getApplicationName());
            }
            if (StringUtils.equals(field, "unit")) {
                return StringUtils.defaultString(instance.getUnitName());
            }
        }

        if (service != null) {
            if (StringUtils.equals(field, "role")) {
                return service.getRole() == MetServiceType.Provider ? "发布 提供者 Provider" : "订阅 消费者 Consumer";
            }
            if (StringUtils.equals(field, "protocol")) {
                return protocols(service);
            }
            if (StringUtils.equals(field, "address")) {
                return addresses(service);
            }
        }

        throw new IllegalArgumentException("Unsupported directory search field: " + field);
    }

    public static boolean protocol(Object record, String protocol) {
        if (StringUtils.isBlank(protocol)) {
            return true;
        }

        EntServiceRuntimeData service;
        if (record instanceof RefServiceRelationData member) {
            service = member.getService();
        } else if (record instanceof EntServiceRuntimeData declaration) {
            service = declaration;
        } else {
            throw new IllegalArgumentException("Protocol filtering requires a service registration directory");
        }

        return Arrays.asList(protocols(service).split(" ")).contains(protocol);
    }

    public static Set<String> protocolNames(EntServiceRuntimeData service) {
        Set<String> names = new TreeSet<>();
        if (service.getRole() == MetServiceType.Consumer) {
            names.addAll(Arrays.asList(service.getUsedProtocols()));
        } else {
            for (RefProtocolMountData endpoint : service.getEndpoints()) {
                String name = endpoint.getProtocolName();
                if (StringUtils.isNotBlank(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    public static String protocols(EntServiceRuntimeData service) {
        if (service.getRole() == MetServiceType.Consumer) {
            return String.join(" ", service.getUsedProtocols());
        }

        Set<String> values = new TreeSet<>();
        for (RefProtocolMountData endpoint : service.getEndpoints()) {
            if (endpoint.getProtocolName() != null) {
                values.add(endpoint.getProtocolName());
            }
            if (endpoint.getProtocol() != null) {
                values.add(endpoint.getProtocol());
            }
        }
        return String.join(" ", values);
    }

    public static String addresses(EntServiceRuntimeData service) {
        Set<String> values = new TreeSet<>();
        for (RefProtocolMountData endpoint : service.getEndpoints()) {
            if (endpoint.getPublishedAddress() != null) {
                values.add(endpoint.getPublishedAddress());
            }
            if (endpoint.getListenAddress() != null) {
                values.add(endpoint.getListenAddress());
            }
        }
        return String.join(" ", values);
    }
}
