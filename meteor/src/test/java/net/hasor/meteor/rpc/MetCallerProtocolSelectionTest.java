/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.*;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 出站调用依据服务声明和目标地址选择唯一逻辑协议。 */
public class MetCallerProtocolSelectionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void emptySelectionUsesDefaultLogicalProtocol() throws Exception {
        // 同一 HTTPS 地址挂载两个逻辑协议，服务不显式选择。
        ConnectorConfig shared = endpoint("shared", "https", 8443, "grpc", "hprose");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(shared), Collections.emptySet(), target)) {
            // 发起调用后应把默认逻辑协议传给连接管理器。
            MetFuture future = host.invoke();
            assertEquals("grpc", host.connectedProtocol.get());
            assertTrue(future.cancel(true));
        }
    }

    @Test
    public void emptySelectionUsesUniqueTargetSchemeWhenDefaultDoesNotMatch() throws Exception {
        // 默认协议为 RSF，但调用方显式连接唯一的 Hprose 地址。
        ConnectorConfig tcp = endpoint("tcp", "rsf", 2181, "rsf");
        ConnectorConfig http = endpoint("http", "hprose", 8080, "hprose");
        InterAddress target = new InterAddress("hprose://127.0.0.1:9080/default");
        try (Host host = new Host("rsf", List.of(tcp, http), Collections.emptySet(), target)) {
            // 未声明协议时，唯一地址挂载可以覆盖不适用的默认协议。
            MetFuture future = host.invoke();
            assertEquals("hprose", host.connectedProtocol.get());
            assertTrue(future.cancel(true));
        }
    }

    @Test
    public void explicitProtocolOverridesDefaultProtocol() throws Exception {
        // 服务显式选择 Hprose，端点默认协议仍为 gRPC。
        ConnectorConfig shared = endpoint("shared", "https", 8443, "grpc", "hprose");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(shared), Set.of("hprose"), target)) {
            // 连接必须使用服务声明，不能回退到默认协议。
            MetFuture future = host.invoke();
            assertEquals("hprose", host.connectedProtocol.get());
            assertTrue(future.cancel(true));
        }
    }

    @Test
    public void targetAliasFiltersSeveralDeclaredProtocols() throws Exception {
        // 服务同时声明 gRPC 和 RSF，目标地址只与 HTTPS 挂载匹配。
        ConnectorConfig http = endpoint("http", "https", 8443, "grpc");
        ConnectorConfig tcp = endpoint("tcp", "rsf", 2181, "rsf");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("rsf", List.of(http, tcp), Set.of("grpc", "rsf"), target)) {
            // 地址别名应将候选收敛为唯一的 gRPC 挂载。
            MetFuture future = host.invoke();
            assertEquals("grpc", host.connectedProtocol.get());
            assertTrue(future.cancel(true));
        }
    }

    @Test
    public void logicalNameTakesPriorityOverSchemeAlias() throws Exception {
        // 一个协议的逻辑名与另一个协议的 HTTPS 别名相同。
        ConnectorConfig shared = endpoint("shared", "https", 8443, "https", "grpc");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(shared), Set.of("https"), target)) {
            // 已有逻辑名命中时不能再把同名地址别名加入候选。
            MetFuture future = host.invoke();
            assertEquals("https", host.connectedProtocol.get());
            assertTrue(future.cancel(true));
        }
    }

    @Test
    public void logicalNameIsNotReinterpretedAfterAddressFiltering() throws Exception {
        // 逻辑协议 https 只挂载在 TCP，gRPC 却使用 https 作为地址别名。
        Map<String, String> endpointOptions = Collections.emptyMap();
        ProtocolConfig logical = ProtocolTestAccess.config("https", "tcp", "https", Collections.emptyMap());
        ProtocolConfig alias = ProtocolTestAccess.config("grpc", "https", "grpc", Collections.emptyMap());
        InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 8443, "default");
        ConnectorConfig shared = new ConnectorConfig(//
                "shared", local, endpointOptions, List.of(logical, alias), true);
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");

        // 地址过滤后不能把已存在的逻辑名重新解释为 gRPC 的 scheme。
        try (Host host = new Host("grpc", List.of(shared), Set.of("https"), target)) {
            MetException failure = failure(host.invoke());
            assertEquals(ProtocolStatus.ProtocolUndefined, failure.getStatus());
            assertEquals(0, host.connectCalls.get());
        }
    }

    @Test
    public void sameAliasLeavesSeveralDeclaredProtocolsAmbiguous() throws Exception {
        // gRPC 和 Hprose 共用 HTTPS，服务又同时声明两者。
        ConnectorConfig shared = endpoint("shared", "https", 8443, "grpc", "hprose");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(shared), Set.of("grpc", "hprose"), target)) {
            // 地址别名不能继续消除歧义时，调用应明确失败且不发起连接。
            MetException failure = failure(host.invoke());
            assertEquals(ProtocolStatus.ProtocolUndefined, failure.getStatus());
            assertTrue(failure.getMessage().contains("protocol is ambiguous"));
            assertEquals(0, host.connectCalls.get());
        }
    }

    @Test
    public void duplicateBoundAndOutboundMountsReportBothCandidates() throws Exception {
        // 入站端点和纯出站端点都挂载同一个 gRPC over HTTPS 能力。
        ConnectorConfig incoming = endpoint("incoming", "https", 8443, true, "grpc");
        ConnectorConfig outgoing = endpoint("outgoing", "https", 0, false, "grpc");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(incoming, outgoing), Set.of("grpc"), target)) {
            // Caller 不能任取一个端点，错误应列出两个候选及其模式。
            MetException failure = failure(host.invoke());
            assertTrue(failure.getMessage().contains("endpoint=incoming"));
            assertTrue(failure.getMessage().contains("mode=bound"));
            assertTrue(failure.getMessage().contains("endpoint=outgoing"));
            assertTrue(failure.getMessage().contains("mode=outbound"));
        }
    }

    @Test
    public void unmatchedAddressAliasFailsBeforeConnecting() throws Exception {
        // 服务只声明 RSF，但目标地址使用 HTTPS。
        ConnectorConfig tcp = endpoint("tcp", "rsf", 2181, "rsf");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("rsf", List.of(tcp), Set.of("rsf"), target)) {
            // 没有符合目标别名的挂载时，调用不能静默选择其他协议。
            MetException failure = failure(host.invoke());
            assertEquals(ProtocolStatus.ProtocolUndefined, failure.getStatus());
            assertTrue(failure.getMessage().contains("has no protocol"));
            assertEquals(0, host.connectCalls.get());
        }
    }

    @Test
    public void requestKeepsTheResolvedMountAddress() throws Exception {
        // 两个逻辑协议共用 HTTPS，单值 scheme 查询会产生歧义。
        ConnectorConfig shared = endpoint("shared", "https", 8443, "grpc", "hprose");
        InterAddress target = new InterAddress("https://127.0.0.1:9443/default");
        try (Host host = new Host("grpc", List.of(shared), Set.of("grpc"), target)) {
            // Caller 按端点和逻辑协议保存本地地址，请求对象不再按 scheme 二次查询。
            MetFuture future = host.invoke();
            InterAddress expected = new InterAddress("https://127.0.0.1:8443/default");
            assertEquals(expected, future.getRequest().getRemoteAddress());
            ProtocolConfig selected = shared.protocols().iterator().next();
            assertEquals(selected.mountId(), host.boundMount.get());
            assertEquals(shared.name(), host.connectedEndpoint.get());
            assertEquals(selected.mountId(), host.connectedMount.get());
            assertTrue(future.cancel(true));
        }
    }

    private static ConnectorConfig endpoint(String name, String scheme, int port, String... protocols) {
        return endpoint(name, scheme, port, true, protocols);
    }

    private static ConnectorConfig endpoint(//
            String name, String scheme, int port, boolean bindEnabled, String... protocols) {
        Map<String, String> endpointOptions = Collections.emptyMap();
        List<ProtocolConfig> mounts = new ArrayList<>();
        for (String protocol : protocols) {
            ProtocolConfig mount = ProtocolTestAccess.config(protocol, scheme, protocol, Collections.emptyMap());
            mounts.add(mount);
        }
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", port, "default");
        return new ConnectorConfig(name, address, endpointOptions, mounts, bindEnabled);
    }

    private static MetException failure(MetFuture future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected protocol selection failure");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof MetException);
            return (MetException) expected.getCause();
        }
    }

    private static final class Host implements AutoCloseable {
        private final ServiceDomain<Echo>     service           = new ServiceDomain<>(Echo.class);
        private final InterAddress            target;
        private final AtomicInteger           connectCalls      = new AtomicInteger();
        private final AtomicReference<String> connectedProtocol = new AtomicReference<>();
        private final AtomicReference<String> connectedEndpoint = new AtomicReference<>();
        private final AtomicReference<String> connectedMount    = new AtomicReference<>();
        private final AtomicReference<String> boundMount        = new AtomicReference<>();
        private final ConnectorManager        manager;
        private final MetCaller               caller;

        @SuppressWarnings("unchecked")
        private Host(//
                String defaultProtocol, List<ConnectorConfig> endpoints, //
                Set<String> selected, InterAddress target) {
            this.target = target;
            this.service.setBindName("Echo");
            this.service.setServiceType(MetServiceType.Consumer);
            this.service.setSerializeType("Java");
            this.service.setDistributed(true);
            for (String protocol : selected) {
                this.service.addBindProtocol(protocol);
            }

            AtomicReference<Collection<ConnectorConfig>> configured = //
                    new AtomicReference<>(Collections.emptyList());
            MetSettings settings = proxy(MetSettings.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "getConnectorConfigs":
                        return configured.get();
                    case "getDefaultProtocol":
                        return defaultProtocol;
                    case "getQueueMaxSize":
                        return 16;
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getDefaultTimeout":
                    case "getRequestTimeout":
                        return 5000;
                    case "getMaximumRequest":
                        return 10;
                    case "getSendLimitPolicy":
                        return SendLimitPolicy.Reject;
                    case "getUnitName":
                        return "default";
                    case "getRequestOptions":
                    case "getResponseOptions":
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting access: " + method);
                }
            });

            MetContext context = proxy(MetContext.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "getApplicationName":
                        return "application";
                    case "getSettings":
                        return settings;
                    case "getDefaultProtocol":
                        return defaultProtocol;
                    case "getClassLoader":
                        return this.getClass().getClassLoader();
                    case "getServiceInfo":
                        return this.service;
                    case "getServiceProvider":
                        return null;
                    case "subscribe":
                        return (Cancellable) () -> true;
                    case "bindAddress":
                        return this.bindAddress(endpoints, arguments);
                    default:
                        throw new AssertionError("Unexpected context access: " + method);
                }
            });

            MetChannel channel = proxy(MetChannel.class, (object, method, arguments) -> {
                if (StringUtils.equals("sendData", method.getName())) {
                    return new BasicFuture<>((MetChannel) object);
                }
                throw new AssertionError("Unexpected channel access: " + method);
            });
            this.manager = new ConnectorManager(context) {
                @Override
                public Future<MetChannel> connect(//
                        String endpointName, String mountId, InterAddress address) {
                    Host.this.connectCalls.incrementAndGet();
                    Host.this.connectedEndpoint.set(endpointName);
                    Host.this.connectedMount.set(mountId);
                    String protocol = Host.this.protocolName(endpoints, endpointName, mountId);
                    Host.this.connectedProtocol.set(protocol);
                    return new BasicFuture<>(channel);
                }

                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    return () -> true;
                }
            };
            this.manager.init();
            configured.set(endpoints);
            this.caller = new MetCaller(this.manager, serviceId -> new Supplier[0]);
        }

        private String protocolName(List<ConnectorConfig> endpoints, String endpointName, String mountId) {
            for (ConnectorConfig endpoint : endpoints) {
                if (StringUtils.equals(endpoint.name(), endpointName)) {
                    ProtocolConfig mount = endpoint.mount(mountId);
                    return mount == null ? null : mount.name();
                }
            }
            return null;
        }

        private InterAddress bindAddress(List<ConnectorConfig> endpoints, Object[] arguments) {
            if (arguments.length != 2) {
                throw new AssertionError("RPC must select a concrete protocol mount");
            }

            String endpointName = (String) arguments[0];
            String mountId = (String) arguments[1];
            this.boundMount.set(mountId);
            for (ConnectorConfig endpoint : endpoints) {
                if (!StringUtils.equals(endpoint.name(), endpointName)) {
                    continue;
                }
                ProtocolConfig protocol = endpoint.mount(mountId);
                if (protocol != null) {
                    InterAddress configured = endpoint.address();
                    return new InterAddress(protocol.scheme(), configured.getHost(), configured.getPort(), //
                            configured.getFormUnit());
                }
            }
            return null;
        }

        private MetFuture invoke() throws Exception {
            InstanceAddressProvider provider = new InstanceAddressProvider(this.target);
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject request = this.caller.createRequest(//
                    provider, this.service, "echo", parameterTypes, arguments);
            return this.caller.invoke(request);
        }

        @Override
        public void close() {
            this.caller.stopAdmission();
            try {
                this.manager.close();
            } finally {
                this.caller.close();
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
        return type.cast(proxy);
    }
}
