/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import org.apache.dubbo.common.URL;

/** Meteor 服务声明与原生 Dubbo URL 之间的无状态转换。 */
final class DubboServiceUrls {
    private DubboServiceUrls() {
    }

    static String serviceInterface(MetBindInfo<?> service) {
        String alias = service.getAliasName("dubbo");
        return StringUtils.isBlank(alias) ? service.getBindType().getName() : alias;
    }

    static Set<String> selectedSchemes(MetContext context, MetBindInfo<?> service) {
        Set<String> schemes = new LinkedHashSet<>();
        for (ConnectorConfig connector : context.getSettings().getConnectorConfigs()) {
            for (ProtocolConfig protocol : connector.protocols()) {
                boolean dubbo = StringUtils.equalsIgnoreCase("dubbo", protocol.name());
                if (!dubbo) {
                    continue;
                }
                RouteMountIndex routes = new RouteMountIndex(connector.name(), protocol);
                if (routes.accepts(service)) {
                    schemes.add(protocol.scheme());
                }
            }
        }
        return schemes;
    }

    static URL provider(MetContext context, MetBindInfo<?> service, InterAddress address, //
            String publishHost, String secureScheme) {
        String host = publishHost == null ? address.getHost() : publishHost;
        boolean wildcardIpv4 = StringUtils.equals("0.0.0.0", host);
        boolean wildcardIpv6 = StringUtils.equals("::", host) || StringUtils.equals("[::]", host);
        if (StringUtils.isBlank(host) || wildcardIpv4 || wildcardIpv6) {
            throw new IllegalArgumentException("Dubbo provider requires a concrete publishHost");
        }
        Map<String, String> parameters = identity(context, service);
        parameters.put("side", "provider");
        parameters.put("category", "providers");
        parameters.put("dynamic", "true");
        parameters.put("enabled", "true");
        parameters.put("serialization", "hessian2");
        parameters.put("prefer.serialization", "hessian2");
        parameters.put("methods", methodNames(service.getBindType()));
        boolean secure = StringUtils.equalsIgnoreCase(secureScheme, address.getSchema());
        parameters.put("ssl-enabled", Boolean.toString(secure));
        parameters.put("meteor.unit", address.getFormUnit());
        return new URL("dubbo", host, address.getPort(), serviceInterface(service), parameters);
    }

    static URL consumer(MetContext context, MetBindInfo<?> service) {
        Map<String, String> parameters = identity(context, service);
        parameters.put("side", "consumer");
        parameters.put("category", "providers");
        parameters.put("check", "false");
        parameters.put("protocol", "dubbo");
        parameters.put("serialization", "hessian2");
        parameters.put("prefer.serialization", "hessian2");
        return new URL("consumer", "127.0.0.1", 0, serviceInterface(service), parameters);
    }

    static URL registration(URL subscription) {
        return subscription.addParameter("category", "consumers").addParameter("dynamic", true);
    }

    static List<InterAddress> providers(MetContext context, MetBindInfo<?> service, //
            List<URL> urls, String secureScheme) {
        Set<InterAddress> addresses = new LinkedHashSet<>();
        Set<String> schemes = selectedSchemes(context, service);
        for (URL provider : urls) {
            if (!matches(service, provider)) {
                continue;
            }
            boolean secure = provider.getParameter("ssl-enabled", false);
            String scheme = secure ? secureScheme : "dubbo";
            if (!schemes.contains(scheme)) {
                continue;
            }
            String unit = provider.getParameter("meteor.unit", context.getSettings().getUnitName());
            InterAddress address = new InterAddress(scheme, provider.getHost(), provider.getPort(), unit);
            addresses.add(address);
        }
        return new ArrayList<>(addresses);
    }

    private static boolean matches(MetBindInfo<?> service, URL provider) {
        boolean dubbo = StringUtils.equalsIgnoreCase("dubbo", provider.getProtocol());
        boolean providerCategory = StringUtils.equals("providers", provider.getParameter("category", "providers"));
        boolean enabled = provider.getParameter("enabled", true) && !provider.getParameter("disabled", false);
        if (!dubbo || !providerCategory || !enabled || provider.getPort() <= 0 || provider.getPort() > 65535) {
            return false;
        }
        boolean sameInterface = StringUtils.equals(serviceInterface(service), provider.getServiceInterface());
        boolean sameGroup = StringUtils.equals(nativeGroup(service), empty(provider.getParameter("group")));
        boolean sameVersion = StringUtils.equals(nativeVersion(service), empty(provider.getParameter("version")));
        return sameInterface && sameGroup && sameVersion;
    }

    private static Map<String, String> identity(MetContext context, MetBindInfo<?> service) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("application", context.getApplicationName());
        parameters.put("interface", serviceInterface(service));
        String group = nativeGroup(service);
        if (!group.isEmpty()) {
            parameters.put("group", group);
        }
        String version = nativeVersion(service);
        if (!version.isEmpty()) {
            parameters.put("version", version);
        }
        parameters.put("dubbo", "2.0.2");
        return parameters;
    }

    private static String methodNames(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (MethodMeta method : ServiceMetaParser.parse(type).getMethods()) {
            if (!method.getJavaMethod().isBridge() && !method.getJavaMethod().isSynthetic()) {
                names.add(method.getName());
            }
        }
        return String.join(",", names);
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    static String nativeGroup(MetBindInfo<?> service) {
        Object group = service.getMetaData("dubbo.registry.group");
        return group == null ? empty(service.getBindGroup()) : (String) group;
    }

    static String nativeVersion(MetBindInfo<?> service) {
        Object version = service.getMetaData("dubbo.registry.version");
        return version == null ? empty(service.getBindVersion()) : (String) version;
    }
}
