/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.admin.MetEndpointFilter;
import net.hasor.meteor.registry.admin.MetServiceEndpoint;
import net.hasor.meteor.registry.admin.MetServiceMember;

/** 管理查询的成员筛选，所有端点条件必须命中同一挂载。 */
final class MemberMatcherUtils {
    private MemberMatcherUtils() {
    }

    public static boolean matches(MetServiceMember member, MetEndpointFilter filter) {
        if (filter == null) {
            return true;
        }
        if (!matches(filter.getInstanceId(), member.getInstance().getInstanceId(), false)) {
            return false;
        }

        boolean anyConnector = StringUtils.isBlank(filter.getConnectorName());
        boolean anyTransport = StringUtils.isBlank(filter.getTransport());
        boolean anyProtocol = StringUtils.isBlank(filter.getProtocol()) && StringUtils.isBlank(filter.getScheme());
        boolean anyLocation = StringUtils.isBlank(filter.getContextPath()) && StringUtils.isBlank(filter.getAddress());
        if (anyConnector && anyTransport && anyProtocol && anyLocation) {
            return true;
        }

        // 所有条件必须匹配同一个协议挂载，返回时仍保留成员的完整端点列表。
        for (MetServiceEndpoint endpoint : member.getEndpoints()) {
            boolean connector = matches(filter.getConnectorName(), endpoint.getConnectorName(), false);
            boolean transport = matches(filter.getTransport(), endpoint.getTransport(), true);
            boolean protocol = matches(filter.getProtocol(), endpoint.getProtocol(), true);
            boolean scheme = matches(filter.getScheme(), endpoint.getScheme(), true);
            boolean path = matches(filter.getContextPath(), endpoint.getContextPath(), false);
            if (!connector || !transport || !protocol || !scheme || !path) {
                continue;
            }

            String address = filter.getAddress();
            boolean configured = matches(address, endpoint.getConfiguredAddress(), false);
            boolean listening = matches(address, endpoint.getListenAddress(), false);
            boolean published = matches(address, endpoint.getPublishedAddress(), false);
            if (configured || listening || published) {
                return true;
            }
        }

        return false;
    }

    private static boolean matches(String expected, String actual, boolean ignoreCase) {
        if (StringUtils.isBlank(expected)) {
            return true;
        }
        if (ignoreCase) {
            return StringUtils.equalsIgnoreCase(expected, actual);
        } else {
            return StringUtils.equals(expected, actual);
        }
    }
}
