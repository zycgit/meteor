/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.support;
import java.util.*;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 管理查询结果的纯映射；不在对象转换中发起存储查询。 */
final class DirectoryMapping {
    private DirectoryMapping() {
    }

    public static DirectoryQuery query(MetDirectoryQuery input) {
        MetDirectoryQuery source = input == null ? new MetDirectoryQuery() : input;
        if (source.getLimit() < 1 || source.getLimit() > 1000) {
            throw new IllegalArgumentException("Directory page limit must be between 1 and 1000");
        }

        DirectoryQuery query = new DirectoryQuery();
        query.setSearch(source.getSearch());
        query.setField(source.getField());
        query.setProtocol(source.getProtocol());
        query.setUnitName(source.getUnitName());
        query.setCursor(source.getCursor());
        query.setLimit(source.getLimit());
        return query;
    }

    public static <S, T> MetPage<T> page(RecordPage<S> source, Function<S, T> mapper) {
        MetPage<T> result = new MetPage<>();
        result.setNextCursor(source.getNextCursor());
        for (S item : source.getItems()) {
            result.getItems().add(mapper.apply(item));
        }
        return result;
    }

    public static <T> MetPage<T> local(List<T> source, MetDirectoryQuery input, Function<T, String> identity) {
        DirectoryQuery query = query(input);
        int offset = query.getCursor() == null ? 0 : Integer.parseInt(query.getCursor());
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid directory cursor");
        }

        List<T> selected = new ArrayList<>();
        for (T item : source) {
            if (matches(item, query, identity.apply(item))) {
                selected.add(item);
            }
        }

        MetPage<T> page = new MetPage<>();
        int end = (int) Math.min(selected.size(), (long) offset + query.getLimit());
        if (offset < end) {
            page.getItems().addAll(selected.subList(offset, end));
        }
        if (end < selected.size()) {
            page.setNextCursor(Integer.toString(end));
        }
        return page;
    }

    private static <T> boolean matches(T item, DirectoryQuery query, String identity) {
        Object record;
        if (item instanceof MetServiceMember member) {
            if (StringUtils.isNotBlank(query.getUnitName()) && !StringUtils.equals(query.getUnitName(), member.getInstance().getUnitName())) {
                return false;
            }

            RefServiceRelationData registration = RegistryJson.copy(member, RefServiceRelationData.class);
            registration.getService().setUsedProtocols(member.getUsedProtocols());
            registration.getService().setEndpoints(RegistryJson.copy(member.getEndpoints(), RefProtocolMountData[].class));
            record = registration;
        } else if (item instanceof MetServiceInfo service) {
            EntServiceRuntimeData registration = RegistryJson.copy(service, EntServiceRuntimeData.class);
            registration.setUsedProtocols(service.getProtocols());
            List<RefProtocolMountData> endpoints = new ArrayList<>();
            for (String protocol : service.getProtocols()) {
                RefProtocolMountData endpoint = new RefProtocolMountData();
                endpoint.setProtocolName(protocol);
                endpoints.add(endpoint);
            }
            registration.setEndpoints(endpoints.toArray(RefProtocolMountData[]::new));
            record = registration;
        } else {
            throw new IllegalArgumentException("Unsupported direct directory object");
        }

        if (!DirectoryFilter.protocol(record, query.getProtocol())) {
            return false;
        }
        if (StringUtils.isBlank(query.getSearch())) {
            return true;
        }

        String text = DirectoryFilter.text(record, query.getField(), identity);
        return text.toLowerCase(Locale.ROOT).contains(query.getSearch().toLowerCase(Locale.ROOT));
    }

    public static String memberIdentity(MetServiceMember member) {
        MetInstanceInfo instance = member.getInstance();
        return instance.getInstanceId() + " " + instance.getIpAddress() + " " + instance.getProcessId();
    }

    public static MetInstanceInfo instance(InstanceStatus status) {
        MetInstanceInfo result = RegistryJson.copy(status.getInstance(), MetInstanceInfo.class);
        EntInstanceRuntimeData runtime = status.getRuntime();
        result.setOnline(runtime != null);
        if (runtime != null) {
            result.setBlock(runtime.isBlock());
            result.setRoles(runtime.getRoles());
            result.setListenAddresses(runtime.getListenAddresses());
            result.setManagementAddress(runtime.getManagementAddresses());
        }
        return result;
    }

    public static MetServiceInfo service(EntServiceCatalogData declaration, ServiceSummary summary) {
        MetServiceInfo info = RegistryJson.copy(declaration, MetServiceInfo.class);
        if (summary != null) {
            info.setProviderCount(summary.getProviderCount());
            info.setConsumerCount(summary.getConsumerCount());
            info.setProtocols(summary.getProtocols());
            if (summary.getProviderCount() > 0) {
                info.setRole(MetServiceType.Provider);
            } else if (summary.getConsumerCount() > 0) {
                info.setRole(MetServiceType.Consumer);
            }
        }
        return info;
    }

    public static MetServiceInfo service(EntServiceRuntimeData registration) {
        MetServiceInfo result = RegistryJson.copy(registration, MetServiceInfo.class);
        boolean provider = registration.getRole() == MetServiceType.Provider;
        result.setProviderCount(provider ? 1 : 0);
        result.setConsumerCount(provider ? 0 : 1);
        Set<String> protocols = new TreeSet<>();
        if (provider) {
            for (RefProtocolMountData endpoint : registration.getEndpoints()) {
                protocols.add(endpoint.getProtocolName());
            }
        } else {
            protocols.addAll(Arrays.asList(registration.getUsedProtocols()));
        }
        result.setProtocols(protocols.toArray(String[]::new));
        return result;
    }

    public static MetServiceMember member(RefServiceRelationData record) {
        MetServiceMember member = new MetServiceMember();
        InstanceStatus status = new InstanceStatus();
        status.setInstance(record.getInstance());
        status.setRuntime(record.getInstance());
        member.setInstance(instance(status));
        member.setService(service(record.getService()));
        member.setEnabled(record.getService().isEnabled());
        member.setUsedProtocols(record.getService().getUsedProtocols());
        member.setEndpoints(RegistryJson.copy(record.getService().getEndpoints(), MetServiceEndpoint[].class));
        return member;
    }
}
