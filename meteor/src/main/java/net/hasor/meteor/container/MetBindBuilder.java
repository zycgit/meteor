/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.RouteTypeEnum;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ServiceDomain;

/**
 * 服务注册器
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetBindBuilder implements MetPublisher {
    private final MetContainer container;

    public MetBindBuilder(MetContainer container) {
        this.container = container;
    }

    @Override
    public MetSettings getSettings() {
        return this.container.getSettings();
    }

    private <T> Supplier<T> toProvider(Class<T> type) {
        return () -> {
            try {
                return type.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                String message = "Cannot construct " + type.getName();
                message += "; register an instance or Supplier instead";
                throw new IllegalArgumentException(message, e);
            }
        };
    }

    public MetPublisher bindFilter(String filterID, MetFilter instance) {
        return this.bindFilter(filterID, () -> instance);
    }

    public MetPublisher bindFilter(String filterID, Class<? extends MetFilter> rsfFilterType) {
        return this.bindFilter(filterID, this.toProvider(rsfFilterType));
    }

    public MetPublisher bindFilter(String filterID, Supplier<? extends MetFilter> provider) {
        this.container.publishFilter(new FilterDefine(filterID, provider));
        return this;
    }

    public <T> LinkedBuilder<T> rsfService(Class<T> type) {
        return new LinkedBuilderImpl<T>(type);
    }

    public <T> ConfigurationBuilder<T> rsfService(Class<T> type, T instance) {
        return this.rsfService(type).toInstance(instance);
    }

    public <T> ConfigurationBuilder<T> rsfService(Class<T> type, Class<? extends T> implementation) {
        return this.rsfService(type).to(implementation);
    }

    public <T> ConfigurationBuilder<T> rsfService(Class<T> type, Supplier<T> provider) {
        return this.rsfService(type).toProvider(provider);
    }

    private class LinkedBuilderImpl<T> implements LinkedBuilder<T> {
        private final ServiceDefine<T> serviceDefine;

        private LinkedBuilderImpl(Class<T> serviceType) {
            this.serviceDefine = new ServiceDefine<T>(serviceType);
            MetSettings settings = MetBindBuilder.this.getSettings();
            MetService serviceInfo = new AnnoMetServiceValue(settings, serviceType);
            ServiceDomain<T> domain = this.serviceDefine.getDomain();
            domain.setServiceType(MetServiceType.Consumer);
            domain.setBindGroup(serviceInfo.group());
            domain.setBindName(serviceInfo.name());
            domain.setBindVersion(serviceInfo.version());
            domain.setSerializeType(serviceInfo.serializeType());
            domain.setClientTimeout(serviceInfo.clientTimeout());
        }

        @Override
        public ConfigurationBuilder<T> group(String group) {
            Objects.requireNonNull(group, "group is null.");
            if (group.contains("/")) {
                throw new IllegalStateException(group + " contain '/'");
            }

            this.serviceDefine.getDomain().setBindGroup(group);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> name(String name) {
            Objects.requireNonNull(name, "name is null.");
            if (name.contains("/")) {
                throw new IllegalStateException(name + " contain '/'");
            }

            this.serviceDefine.getDomain().setBindName(name);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> aliasName(String aliasType, String aliasName) {
            aliasType = Objects.requireNonNull(aliasType, "aliasType is null.");
            aliasName = Objects.requireNonNull(aliasName, "aliasName is null.");
            this.serviceDefine.getDomain().putAliasName(aliasType, aliasName);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> version(String version) {
            Objects.requireNonNull(version, "version is null.");
            if (version.contains("/")) {
                throw new IllegalStateException(version + " contain '/'");
            }

            this.serviceDefine.getDomain().setBindVersion(version);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> timeout(int clientTimeout) {
            if (clientTimeout < 1) {
                throw new IllegalStateException("clientTimeout must be greater than 0");
            }

            this.serviceDefine.getDomain().setClientTimeout(clientTimeout);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> serialize(String serializeType) {
            Objects.requireNonNull(serializeType, "serializeType is null.");
            if (serializeType.contains("/")) {
                throw new IllegalStateException(serializeType + " contain '/'");
            }

            this.serviceDefine.getDomain().setSerializeType(serializeType);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> bindResource(Object bindResource) {
            this.serviceDefine.getDomain().setBindResource(bindResource);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> protocol(String protocol, String... protocolArrays) {
            if (StringUtils.isNotBlank(protocol)) {
                this.serviceDefine.getDomain().addBindProtocol(this.protocolSelector(protocol));
            }

            for (String prot : protocolArrays) {
                if (StringUtils.isNotBlank(prot)) {
                    this.serviceDefine.getDomain().addBindProtocol(this.protocolSelector(prot));
                }
            }
            return this;
        }

        @Override
        public ConfigurationBuilder<T> contextPathByPrefix(String protocol, String defaultContextPath, String... additionalContextPaths) {
            Objects.requireNonNull(additionalContextPaths, "additionalPaths");
            List<String> paths = new ArrayList<>(additionalContextPaths.length + 1);
            paths.add(defaultContextPath);
            Collections.addAll(paths, additionalContextPaths);
            String protocolName = this.protocolSelector(protocol);
            ContextPath context = new ContextPath(paths, false);
            this.serviceDefine.getDomain().setBindContextPaths(protocolName, context);
            return this;
        }

        @Override
        public ConfigurationBuilder<T> contextPathByExact(String protocol, String path, String... additionalPaths) {
            Objects.requireNonNull(additionalPaths, "additionalPaths");
            List<String> paths = new ArrayList<>(additionalPaths.length + 1);
            paths.add(path);
            Collections.addAll(paths, additionalPaths);
            String protocolName = this.protocolSelector(protocol);
            ContextPath context = new ContextPath(paths, true);
            this.serviceDefine.getDomain().setBindContextPaths(protocolName, context);
            return this;
        }

        /** 逻辑协议名优先；地址 scheme 只在能够唯一确定逻辑协议时作为兼容别名。 */
        private String protocolSelector(String selection) {
            if (StringUtils.isBlank(selection)) {
                throw new IllegalArgumentException("Service protocol must not be blank");
            }

            String selector = selection.trim();

            // 先在全部端点中解析逻辑协议名，同一逻辑协议可以挂载在多个传输端点上。
            for (ConnectorConfig endpoint : MetBindBuilder.this.getSettings().getConnectorConfigs()) {
                for (ProtocolConfig protocol : endpoint.protocols()) {
                    if (StringUtils.equalsIgnoreCase(selector, protocol.name())) {
                        return protocol.name();
                    }
                }
            }

            // 没有逻辑名匹配时，公开地址 scheme 必须唯一对应一个逻辑协议。
            String selected = null;
            String selectedScheme = null;
            for (ConnectorConfig endpoint : MetBindBuilder.this.getSettings().getConnectorConfigs()) {
                for (ProtocolConfig protocol : endpoint.protocols()) {
                    if (!StringUtils.equalsIgnoreCase(selector, protocol.scheme())) {
                        continue;
                    }
                    if (selected != null && !StringUtils.equalsIgnoreCase(selected, protocol.name())) {
                        throw new IllegalArgumentException("Service protocol alias is ambiguous: " + selection);
                    }
                    selected = protocol.name();
                    selectedScheme = protocol.scheme();
                }
            }
            if (selectedScheme != null) {
                return selectedScheme;
            }
            throw new IllegalArgumentException("Service selected unknown protocol: " + selection);
        }

        public ConfigurationBuilder<T> bindFilter(String filterID, MetFilter instance) {
            Objects.requireNonNull(instance);
            this.serviceDefine.addRsfFilter(new FilterDefine(filterID, () -> instance));
            return this;
        }

        public ConfigurationBuilder<T> bindFilter(String filterID, Supplier<? extends MetFilter> provider) {
            this.serviceDefine.addRsfFilter(new FilterDefine(filterID, Objects.requireNonNull(provider)));
            return this;
        }

        @Override
        public FilterBindBuilder<T> bindFilter(String filterID, Class<? extends MetFilter> rsfFilterType) {
            Supplier<? extends MetFilter> provider = MetBindBuilder.this.toProvider(rsfFilterType);
            this.serviceDefine.addRsfFilter(new FilterDefine(filterID, provider));
            return this;
        }

        @Override
        public ConfigurationBuilder<T> to(final Class<? extends T> implementation) {
            return this.toProvider(MetBindBuilder.this.toProvider(implementation));
        }

        @Override
        public ConfigurationBuilder<T> toInstance(T instance) {
            return this.toProvider(() -> instance);
        }

        @Override
        public ConfigurationBuilder<T> toProvider(Supplier<? extends T> provider) {
            this.serviceDefine.getDomain().setServiceType(MetServiceType.Provider);
            this.serviceDefine.setCustomerProvider(provider);
            return this;
        }

        @Override
        public RegisterBuilder<T> bindAddress(String rsfHost, int port) throws UnknownHostException {
            String unitName = MetBindBuilder.this.getSettings().getUnitName();
            return this.bindAddress(new InterAddress(rsfHost, port, unitName));
        }

        @Override
        public RegisterBuilder<T> bindAddress(String rsfURI, String... array) throws URISyntaxException, UnknownHostException {
            if (!StringUtils.isBlank(rsfURI)) {
                this.bindAddress(new InterAddress(rsfURI));
            }

            for (String bindItem : array) {
                this.bindAddress(new InterAddress(bindItem));
            }

            return this;
        }

        @Override
        public RegisterBuilder<T> bindAddress(URI rsfURI, URI... array) {
            if (InterAddress.checkFormat(rsfURI)) {
                this.bindAddress(new InterAddress(rsfURI));
            }

            for (URI bindItem : array) {
                if (!InterAddress.checkFormat(bindItem)) {
                    throw new IllegalStateException(bindItem + " check fail.");
                }

                this.bindAddress(new InterAddress(bindItem));
            }
            return this;
        }

        public RegisterBuilder<T> bindAddress(InterAddress rsfAddress, InterAddress... array) {
            if (rsfAddress != null) {
                this.serviceDefine.addAddress(rsfAddress);
            }

            for (InterAddress bindItem : array) {
                if (bindItem == null) {
                    continue;
                }
                this.serviceDefine.addAddress(bindItem);
            }
            return this;
        }

        @Override
        public RegisterBuilder<T> asAloneThreadPool() {
            this.serviceDefine.getDomain().setSharedThreadPool(false);
            return this;
        }

        @Override
        public RegisterBuilder<T> asMessage() {
            this.serviceDefine.getDomain().setMessage(true);
            return this;
        }

        @Override
        public RegisterBuilder<T> asDistributedCall() {
            this.serviceDefine.getDomain().setDistributed(true);
            return this;
        }

        @Override
        public RegisterBuilder<T> asShadow() {
            this.serviceDefine.getDomain().setShadow(true);
            return this;
        }

        public MetBindInfo<T> register() {
            MetBindBuilder.this.container.publishService(this.serviceDefine);
            return this.serviceDefine;
        }

        @Override
        public RegisterBuilder<T> updateFlowControl(String flowControl) {
            this.serviceDefine.setFlowControl(flowControl);
            return this;
        }

        @Override
        public RegisterBuilder<T> updateArgsRoute(String scriptBody) {
            this.serviceDefine.setRouteScript(RouteTypeEnum.ArgsLevel, scriptBody);
            return this;
        }

        @Override
        public RegisterBuilder<T> updateMethodRoute(String scriptBody) {
            this.serviceDefine.setRouteScript(RouteTypeEnum.MethodLevel, scriptBody);
            return this;
        }

        @Override
        public RegisterBuilder<T> updateServiceRoute(String scriptBody) {
            this.serviceDefine.setRouteScript(RouteTypeEnum.ServiceLevel, scriptBody);
            return this;
        }
    }
}
