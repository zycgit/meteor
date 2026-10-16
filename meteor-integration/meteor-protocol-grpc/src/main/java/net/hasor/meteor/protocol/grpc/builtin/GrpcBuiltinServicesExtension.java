/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.builtin;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** 扩展只拥有自己发布的标准服务和事件订阅，不建立第二个 gRPC 服务器。 */
final class GrpcBuiltinServicesExtension implements MetExtension {
    private final MetContext           context;
    private final List<ProtocolConfig> mounts = new ArrayList<>();
    private final GrpcHealthEndpoint   health;

    GrpcBuiltinServicesExtension(MetContext context, boolean health, boolean reflection) throws Exception {
        this.context = context;
        for (ConnectorConfig connector : context.getSettings().getConnectorConfigs()) {
            for (ProtocolConfig mount : connector.protocols()) {
                if (StringUtils.equalsIgnoreCase(mount.factoryName(), "grpc")) {
                    if (!Boolean.parseBoolean(mount.option("streamBody", "false"))) {
                        String message = "Configure streamBody=true for gRPC Health Watch and Reflection";
                        throw new IllegalArgumentException(message);
                    }
                    this.mounts.add(mount);
                }
            }
        }
        if (this.mounts.isEmpty()) {
            throw new IllegalArgumentException("GrpcBuiltinServicesFactory requires a gRPC protocol mount");
        }
        // 聚合入口位于 grpc 子目录，导入仍以资源根目录为基准，保留标准描述文件名。
        String resourceName = "grpc/meteor-grpc-services.proto";
        URL source = GrpcBuiltinServicesFactory.class.getResource("/META-INF/meteor/protocol/" + resourceName);
        GrpcProtoResource resource = new GrpcProtoResource(source);
        this.health = health ? new GrpcHealthEndpoint(this, context) : null;

        try {
            if (health) {
                this.publish(GrpcHealthService.class, this.health, "grpc.health.v1.Health", resource);
            }
            if (reflection) {
                GrpcReflectionService v1 = new GrpcReflectionEndpoint(this);
                this.publish(GrpcReflectionService.class, v1, "grpc.reflection.v1.ServerReflection", resource);
                GrpcReflectionV1AlphaService alpha = new GrpcReflectionV1AlphaEndpoint(v1);
                String alphaName = "grpc.reflection.v1alpha.ServerReflection";
                this.publish(GrpcReflectionV1AlphaService.class, alpha, alphaName, resource);
            }
        } catch (Exception failure) {
            this.close();
            throw failure;
        }
    }

    private <T> void publish(Class<T> type, T implementation, String name, GrpcProtoResource resource) {
        ConfigurationBuilder<T> binding = this.context.publisher().rsfService(type, implementation);
        binding.name(name).aliasName("grpc", name).serialize("Protobuf").bindResource(resource);
        String first = this.mounts.get(0).name();
        String[] additional = this.mounts.stream().skip(1).map(ProtocolConfig::name).toArray(String[]::new);
        binding.protocol(first, additional).timeout(Integer.MAX_VALUE);
        List<String> paths = new ArrayList<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(type)) {
            paths.add("/" + name + "/" + method.getName());
        }
        String[] additionalPaths = paths.stream().skip(1).toArray(String[]::new);
        for (ProtocolConfig mount : this.mounts) {
            // 标准方法使用完整根路径，不继承业务默认 context，也不开放服务目录。
            binding.contextPathByExact(mount.name(), paths.get(0), additionalPaths);
        }
        binding.register();
    }

    List<MetBindInfo<?>> published() {
        List<MetBindInfo<?>> result = new ArrayList<>();
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
                continue;
            }
            if (StringUtils.isBlank(service.getAliasName("grpc"))) {
                continue;
            }
            for (ProtocolConfig mount : this.mounts) {
                if (mount.accepts(service.getBindProtocols())) {
                    result.add(service);
                    break;
                }
            }
        }
        return result;
    }

    boolean online() {
        return this.context.isOnline();
    }

    @Override
    public <T> T getService(Class<T> type) {
        return null;
    }

    @Override
    public void beforeStart() {
    }

    @Override
    public void afterStart() {
        if (this.health != null) {
            this.health.refresh();
        }
    }

    @Override
    public void close() {
        if (this.health != null) {
            this.health.close();
        }
    }
}
