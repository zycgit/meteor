/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.rpc.MetFilterProvider;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** RPC 门面、代理与宿主装配的单元契约，受控通道不代表真实网络。 */
public class RpcModuleTest {
    public interface Echo {
        String echo(String text);

        String fail(String text);

        String getTarget();

        String setTarget(String text);

        String toString(String text);
    }

    public static class EchoImpl implements Echo {
        public String echo(String text) {
            return text;
        }

        public String fail(String text) {
            throw new IllegalArgumentException(text);
        }

        public String getTarget() {
            return "business target";
        }

        public String setTarget(String text) {
            return text;
        }

        public String toString(String text) {
            return text;
        }
    }

    public interface EchoView {
        String echo(String text);
    }

    @Test
    public void zeroServiceTimeoutUsesDefaultForSynchronousCalls() throws Exception {
        // 服务超时为 0 时，同步客户端回退到宿主默认超时。
        try (Host host = new Host(true)) {
            host.service.setClientTimeout(0);
            Class<?>[] types = { String.class };
            Object[] arguments = { "default timeout" };
            Object result = host.client.syncInvoke(host.service, "echo", types, arguments);
            assertEquals("default timeout", result);
            assertEquals(6000, host.localTimeout.get());
        }
    }

    @Test
    public void negativeServiceTimeoutUsesDefaultForSynchronousCalls() throws Exception {
        // 服务超时为 -1 时，同步客户端回退到宿主默认超时。
        try (Host host = new Host(true)) {
            host.service.setClientTimeout(-1);
            Class<?>[] types = { String.class };
            Object[] arguments = { "default timeout" };
            Object result = host.client.syncInvoke(host.service, "echo", types, arguments);
            assertEquals("default timeout", result);
            assertEquals(6000, host.localTimeout.get());
        }
    }

    @Test
    public void proxyObjectMethodsStayLocal() throws Exception {
        // 创建两个代理，Object 的身份方法应在客户端本地完成。
        try (Host host = new Host(true)) {
            Echo first = host.client.getRemote(host.service);
            Echo second = host.client.getRemote(host.service);
            assertEquals(first, first);
            assertNotEquals(first, second);
            assertNotEquals(null, first);
            assertEquals(System.identityHashCode(first), first.hashCode());
            assertTrue(first.toString().contains(host.service.getBindID()));

            // 身份查询不选择地址、不发送请求，也不附加额外代理接口。
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
            assertArrayEquals(new Class<?>[] { Echo.class }, first.getClass().getInterfaces());
        }
    }

    @Test
    public void businessGetterNameRemainsCallable() throws Exception {
        // 接口中的 getter 名称属于业务方法，需要进入调用链。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote(host.service);
            assertEquals("business target", remote.getTarget());
            assertEquals(1, host.localFilters.get());
            assertEquals(1, host.remoteFilters.get());
        }
    }

    @Test
    public void businessSetterNameRemainsCallable() throws Exception {
        // 接口中的 setter 名称仍传递参数并返回业务结果。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote(host.service);
            assertEquals("new value", remote.setTarget("new value"));
            assertEquals(1, host.localFilters.get());
            assertEquals(1, host.remoteFilters.get());
        }
    }

    @Test
    public void overloadedToStringRemainsCallable() throws Exception {
        // 带参数的 toString 是接口业务方法，不能当作 Object 方法短路。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote(host.service);
            assertEquals("overloaded", remote.toString("overloaded"));
            assertEquals(1, host.localFilters.get());
            assertEquals(1, host.remoteFilters.get());
        }
    }

    @Test
    public void sameServiceCanBeWrappedAsDifferentInterfaces() throws Exception {
        // 同一绑定 ID 可以创建不同接口视图，调用都指向原服务。
        try (Host host = new Host(true)) {
            Echo original = host.client.getRemoteByID(host.service.getBindID());
            EchoView view = host.client.wrapperByID(host.service.getBindID(), EchoView.class);
            assertEquals("first", original.echo("first"));
            assertEquals("second", view.echo("second"));
            assertArrayEquals(new Class<?>[] { EchoView.class }, view.getClass().getInterfaces());
        }
    }

    @Test
    public void serviceCoordinatesResolveAClientProxy() throws Exception {
        // 通过组、名称、版本定位服务并调用。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote("RSF", "Echo", "1.0.0");
            assertEquals("lookup", remote.echo("lookup"));
        }
    }

    @Test
    public void interfaceTypeResolvesAClientProxy() throws Exception {
        // 通过业务接口类型创建包装代理。
        try (Host host = new Host(true)) {
            Echo remote = host.client.wrapper(Echo.class);
            assertEquals("type", remote.echo("type"));
        }
    }

    @Test
    public void serviceCoordinatesResolveAnAlternateView() throws Exception {
        // 使用服务坐标与另一接口视图创建代理。
        try (Host host = new Host(true)) {
            EchoView remote = host.client.wrapper("RSF", "Echo", "1.0.0", EchoView.class);
            assertEquals("alias", remote.echo("alias"));
        }
    }

    @Test
    public void concreteClassesCannotBeWrapped() throws Exception {
        // 代理只接受接口，错误类型应在调用前被拒绝。
        try (Host host = new Host(true)) {
            UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, () -> {
                host.client.wrapper(host.service, EchoImpl.class);
            });
            assertTrue(failure.getMessage().contains("interface"));
            assertTrue(host.sent.isEmpty());
        }
    }

    @Test
    public void missingServiceIdRejectsRemoteLookup() throws Exception {
        // 按绑定 ID 查找代理时，缺失绑定应立即失败。
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                host.client.getRemoteByID("missing");
            });
            assertTrue(failure.getMessage().contains("undefined"));

            // 查找错误不能触发地址选择或发送。
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void missingServiceCoordinatesRejectRemoteLookup() throws Exception {
        // 按服务坐标查找代理时，缺失绑定应立即失败。
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                host.client.getRemote("RSF", "missing", "1.0.0");
            });
            assertTrue(failure.getMessage().contains("undefined"));

            // 查找错误不能触发地址选择或发送。
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void missingServiceIdRejectsWrapperLookup() throws Exception {
        // 按绑定 ID 查找接口包装时，缺失绑定应立即失败。
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                host.client.wrapperByID("missing", Echo.class);
            });
            assertTrue(failure.getMessage().contains("undefined"));

            // 查找错误不能触发地址选择或发送。
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void missingServiceTypeRejectsWrapperLookup() throws Exception {
        // 按接口类型查找包装时，缺失绑定应立即失败。
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                host.client.wrapper(Echo.class);
            });
            assertTrue(failure.getMessage().contains("undefined"));

            // 查找错误不能触发地址选择或发送。
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void missingServiceCoordinatesReturnNullWrapper() throws Exception {
        // 按服务坐标请求可选包装时，缺失服务以空值表示。
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            Echo remote = host.client.wrapper("RSF", "missing", "1.0.0", Echo.class);
            assertNull(remote);
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void clientsKeepIndependentProvidersAndDynamicRoutingRunsPerCall() throws Exception {
        // 一个动态地址提供者与一个固定提供者共享调用器，配置应互不影响。
        try (Host host = new Host(true)) {
            InterAddress first = new InterAddress("rsf://localhost:2181/default");
            InterAddress second = new InterAddress("rsf://localhost:2182/default");
            AtomicReference<InterAddress> selected = new AtomicReference<>(first);
            AtomicInteger selections = new AtomicInteger();
            AddressProvider dynamic = new AddressProvider() {
                public boolean isDistributed() {
                    return true;
                }

                public InterAddress get(RouteContext input) {
                    String serviceID = input.getServiceId();
                    String method = (String) input.getMethod().get("name");
                    Object[] arguments = input.getArgs();
                    assertEquals(host.service.getBindID(), serviceID);
                    assertEquals("echo", method);
                    assertEquals(1, arguments.length);
                    selections.incrementAndGet();
                    return selected.get();
                }
            };

            // 动态代理逐次选择地址，固定代理仍保持自身地址。
            Echo routed = new MetClientImpl(host.caller, dynamic).getRemote(host.service);
            Echo fixed = new MetClientImpl(host.caller, new InstanceAddressProvider(first)).getRemote(host.service);
            assertEquals("one", routed.echo("one"));
            selected.set(second);
            assertEquals("two", routed.echo("two"));
            assertEquals("fixed", fixed.echo("fixed"));

            // 检查三次发送的目标以及动态选择的次数。
            assertSame(first, host.targets.remove());
            assertSame(second, host.targets.remove());
            assertSame(first, host.targets.remove());
            assertEquals(2, selections.get());
            assertTrue(host.callerManager.isInitialized());
        }
    }

    @Test
    public void dataCallbackReceivesTheBusinessResultOnce() throws Exception {
        // 数据回调接收一次业务返回值。
        try (Host host = new Host(true)) {
            Callback<Object> callback = new Callback<>();
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "data" };
            host.client.callbackInvoke(host.service, "echo", parameterTypes, arguments, callback);
            assertEquals("data", callback.result.get(2, TimeUnit.SECONDS));
            assertEquals(1, callback.notifications.get());
        }
    }

    @Test
    public void responseCallbackPreservesNullBusinessData() throws Exception {
        // 完整响应回调仍保留合法的空业务返回值。
        try (Host host = new Host(true)) {
            Callback<MetResponse> callback = new Callback<>();
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { null };
            host.client.callbackRequest(host.service, "echo", parameterTypes, arguments, callback);
            MetResponse response = callback.result.get(2, TimeUnit.SECONDS);
            assertNull(response.getData());
            assertEquals(1, callback.notifications.get());
        }
    }

    @Test
    public void dataCallbackReceivesTheBusinessFailureOnce() throws Exception {
        // 业务失败通过失败回调传播，且只通知一次。
        try (Host host = new Host(true)) {
            Callback<Object> callback = new Callback<>();
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "bad" };
            host.client.callbackInvoke(host.service, "fail", parameterTypes, arguments, callback);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                callback.result.get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof MetException);
            assertEquals(1, callback.notifications.get());
        }
    }

    @Test
    public void dataCallbackReceivesCancelledWriteAsFailure() throws Exception {
        // 准备在发送阶段取消写入的连接器。
        try (Host host = new Host(false)) {
            host.cancelWrites = true;
            Callback<Object> data = new Callback<>();
            Class<?>[] parameterTypes = { String.class };

            // 数据回调通过实际发送链路接收取消造成的失败。
            Object[] arguments = { "data" };
            host.client.callbackInvoke(host.service, "echo", parameterTypes, arguments, data);
            assertTrue(data.result.getCause() instanceof CancellationException);
            assertFalse(data.result.isCancelled());
            assertEquals(1, data.notifications.get());
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void responseCallbackReceivesCancelledWriteAsFailure() throws Exception {
        // 准备在发送阶段取消写入的连接器。
        try (Host host = new Host(false)) {
            host.cancelWrites = true;
            Callback<MetResponse> response = new Callback<>();
            Class<?>[] parameterTypes = { String.class };

            // 完整响应回调采用与发送失败一致的通知方式。
            Object[] arguments = { "response" };
            host.client.callbackRequest(host.service, "echo", parameterTypes, arguments, response);
            assertTrue(response.result.getCause() instanceof CancellationException);
            assertFalse(response.result.isCancelled());
            assertEquals(1, response.notifications.get());
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void nullDataCallbackStillSubmitsTheCall() throws Exception {
        // 无数据回调时调用仍进入发送链路。
        try (Host host = new Host(false)) {
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "data" };
            host.client.callbackInvoke(host.service, "echo", parameterTypes, arguments, null);
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void nullResponseCallbackStillSubmitsTheCall() throws Exception {
        // 无响应回调时调用仍进入发送链路。
        try (Host host = new Host(false)) {
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "response" };
            host.client.callbackRequest(host.service, "echo", parameterTypes, arguments, null);
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void closedCallerRejectsExistingClientWithoutSending() throws Exception {
        // 保留已创建的客户端以及两个等待中的请求。
        try (Host host = new Host(false)) {
            host.invoke();
            host.invoke();
            assertEquals(2, host.sent.size());

            // 调用器关闭后，旧客户端不能继续发送请求。
            host.caller.close();
            MetFuture rejected = host.invoke();
            assertTrue(rejected.getCause() instanceof IllegalStateException);
            assertEquals(2, host.sent.size());
        }
    }

    private static class Callback<T> implements FutureCallback<T> {
        private final BasicFuture<T> result        = new BasicFuture<>();
        private final AtomicInteger  notifications = new AtomicInteger();

        public void completed(T value) {
            this.notifications.incrementAndGet();
            this.result.completed(value);
        }

        public void failed(Throwable failure) {
            this.notifications.incrementAndGet();
            this.result.failed(failure);
        }
    }

    @Test
    public void clientAndServerUseHostFiltersWithoutRuntimeAssembly() throws Exception {
        // 使用宿主契约直接装配调用模块，受控通道连接两端调用链。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote(host.service);
            assertEquals("hello", remote.echo("hello"));
            assertEquals(1, host.localFilters.get());
            assertEquals(1, host.remoteFilters.get());
        }
    }

    @Test
    public void nullResultPassesThroughBothHostFilterChains() throws Exception {
        // 空值结果通过两端过滤器链仍保持为空。
        try (Host host = new Host(true)) {
            Echo remote = host.client.getRemote(host.service);
            assertNull(remote.echo(null));
            assertEquals(1, host.localFilters.get());
            assertEquals(1, host.remoteFilters.get());
        }
    }

    @Test
    public void synchronousClientReceivesBusinessFailure() throws Exception {
        // 同步门面将服务端错误交给调用方。
        try (Host host = new Host(true)) {
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "failure" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                host.client.syncInvoke(host.service, "fail", parameterTypes, arguments);
            });
            assertTrue(failure.getCause() instanceof MetException);
        }
    }

    @Test
    public void coreRpcDoesNotDependOnTheHprosePlugin() throws Exception {
        // 核心 RPC 契约测试的运行时不应包含可选 Hprose 插件。
        ClassLoader loader = this.getClass().getClassLoader();
        assertNull(loader.getResource("net/hasor/meteor/protocol/hprose/HproseProtocol.class"));
    }

    private static class Host implements AutoCloseable {
        final ServiceDomain<Echo>           service       = new ServiceDomain<>(Echo.class);
        final BlockingQueue<RequestPayload> sent          = new LinkedBlockingQueue<>();
        final BlockingQueue<InterAddress>   targets       = new LinkedBlockingQueue<>();
        final AtomicInteger                 localFilters  = new AtomicInteger();
        final AtomicInteger                 remoteFilters = new AtomicInteger();
        final AtomicInteger                 localTimeout  = new AtomicInteger();
        final ControlledManager             callerManager;
        final ControlledManager             serverManager;
        final MetCaller                     caller;
        final MetCaller                     server;
        final MetClientImpl                 client;
        final MetChannel                    outgoing;
        final MetChannel                    incoming;
        boolean serviceAvailable = true;
        boolean cancelWrites;

        @SuppressWarnings("unchecked")
        Host(boolean deliver) throws Exception {
            this.service.setBindName("Echo");
            this.service.setServiceType(MetServiceType.Provider);
            this.service.setSerializeType("Java");
            this.service.setDistributed(true);
            InterAddress peer = new InterAddress("rsf://127.0.0.1:2181/default");
            Map<String, String> endpointOptions = Collections.emptyMap();
            ProtocolConfig protocol = ProtocolTestAccess.config("rsf", "rsf", "rsf", Collections.emptyMap());
            InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
            ConnectorConfig endpoint = new ConnectorConfig(//
                    "rpc", local, endpointOptions, Collections.singletonList(protocol), false);
            AtomicReference<Collection<ConnectorConfig>> configured = new AtomicReference<>(Collections.emptyList());
            // 配置公开宿主契约，测试不创建真实连接器。
            MetSettings settings = proxy(MetSettings.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getConnectorConfigs":
                        return configured.get();
                    case "getDefaultProtocol":
                        return "rsf";
                    case "getQueueMaxSize":
                        return 16;
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getRequestTimeout":
                        return 0;
                    case "getDefaultTimeout":
                        return 6000;
                    case "getMaximumRequest":
                        return 100;
                    case "getUnitName":
                        return "default";
                    case "getRequestOptions":
                    case "getResponseOptions":
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + m);
                }
            });
            JavaSerializeCoder coder = new JavaSerializeCoder();
            MetContext context = proxy(MetContext.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getApplicationName":
                        return "application";
                    case "getDefaultProtocol":
                        return "rsf";
                    case "getSettings":
                        return settings;
                    case "getSerializeCoder":
                        return coder;
                    case "getClassLoader":
                        return this.getClass().getClassLoader();
                    case "subscribe":
                        return (Cancellable) () -> true;
                    case "getServiceInfo":
                        return this.serviceAvailable ? this.service : null;
                    case "getServiceProvider":
                        return (Supplier<Echo>) EchoImpl::new;
                    case "bindAddress":
                        return peer;
                    default:
                        throw new AssertionError("Unexpected context access: " + m);
                }
            });
            // 两端共用过滤器契约，但分别记录出站和入站调用次数。
            MetFilter filter = (request, response, chain) -> {
                if (request.isLocal()) {
                    this.localFilters.incrementAndGet();
                    this.localTimeout.set(request.getTimeout());
                } else {
                    this.remoteFilters.incrementAndGet();
                }
                chain.doFilter(request, response);
            };
            MetFilterProvider filters = id -> {
                assertEquals(this.service.getBindID(), id);
                return new Supplier[] { (Supplier<MetFilter>) () -> filter };
            };
            // 受控通道仅交付真实调用器产生的报文，并可注入取消写入。
            this.incoming = proxy(MetChannel.class, (object, method, arguments) -> {
                String methodName = method.getName();
                if (StringUtils.equals("getProtocol", methodName)) {
                    return "rsf";
                }
                if (StringUtils.equals("getLocal", methodName) || StringUtils.equals("getRemote", methodName)) {
                    return peer;
                }
                if (StringUtils.equals("isActive", methodName)) {
                    return true;
                }
                if (StringUtils.equals("sendData", methodName)) {
                    ResponsePayload response = (ResponsePayload) arguments[0];
                    this.deliverResponse(response);
                    return new BasicFuture<>((MetChannel) object);
                }
                throw new AssertionError("Unexpected incoming channel access: " + method);
            });
            this.outgoing = proxy(MetChannel.class, (object, method, arguments) -> {
                String methodName = method.getName();
                if (StringUtils.equals("getProtocol", methodName)) {
                    return "rsf";
                }
                if (StringUtils.equals("getLocal", methodName) || StringUtils.equals("getRemote", methodName)) {
                    return peer;
                }
                if (StringUtils.equals("isActive", methodName)) {
                    return true;
                }
                if (StringUtils.equals("sendData", methodName)) {
                    RequestPayload request = (RequestPayload) arguments[0];
                    request.setReceiveTime(System.currentTimeMillis());
                    this.sent.add(request);
                    if (deliver) {
                        this.deliverRequest(request);
                    }
                    BasicFuture<MetChannel> written = new BasicFuture<>();
                    if (this.cancelWrites) {
                        written.cancel();
                    } else {
                        written.completed((MetChannel) object);
                    }
                    return written;
                }
                throw new AssertionError("Unexpected outgoing channel access: " + method);
            });
            // 管理器保留真实初始化和关闭流程，只替换传输连接与定时调度。
            this.callerManager = new ControlledManager(context) {
                @Override
                public BasicFuture<MetChannel> connect(//
                        String endpointName, String protocol, InterAddress address) {
                    Host.this.targets.add(address);
                    return new BasicFuture<>(Host.this.outgoing);
                }

                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    // 此处只控制门面回调完成时机，时限由调用器及真实网络场景单独覆盖。
                    return () -> true;
                }
            };
            this.serverManager = new ControlledManager(context);
            this.callerManager.init();
            this.serverManager.init();
            configured.set(Collections.singletonList(endpoint));
            this.server = new MetCaller(this.serverManager, filters);
            this.caller = new MetCaller(this.callerManager, filters);
            this.client = new MetClientImpl(this.caller, new InstanceAddressProvider(peer));
        }

        private void deliverRequest(RequestPayload request) {
            this.serverManager.deliver(this.incoming, request.getRequestID(), request);
        }

        private void deliverResponse(ResponsePayload response) {
            this.callerManager.deliver(this.outgoing, response.getRequestID(), response);
        }

        MetFuture invoke() {
            Class<?>[] types = { String.class };
            return this.client.asyncInvoke(this.service, "echo", types, new Object[] { "value" });
        }

        public void close() {
            this.caller.stopAdmission();
            this.server.stopAdmission();
            try {
                this.callerManager.close();
                this.serverManager.close();
            } finally {
                this.caller.close();
                this.server.close();
            }
        }
    }

    private static class ControlledManager extends ConnectorManager {
        private ConnectorSubscriber subscriber;

        private ControlledManager(MetContext context) {
            super(context);
        }

        @Override
        public void subscribe(ConnectorSubscriber subscriber) {
            super.subscribe(subscriber);
            this.subscriber = subscriber;
        }

        private void deliver(MetChannel channel, long requestId, Payload payload) {
            this.subscriber.onMessage(channel, requestId, payload);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
