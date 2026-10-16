/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用内存 SPI 对端验证 Caller、连接器订阅与请求生命周期的单元契约。 */
public class MetCallerTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void localProviderBypassesRpcFiltersAndNetwork() throws Exception {
        // 默认服务恢复本地优先，并用工厂计数本地实例的获取次数。
        try (Host client = new Host(10000, "normal")) {
            client.service.setDistributed(false);
            AtomicInteger instances = new AtomicInteger();
            client.serviceProvider = () -> {
                instances.incrementAndGet();
                return value -> value + "-local";
            };

            // 固定目标地址不改变本地优先，调用直接在当前进程完成。
            MetFuture call = client.invoke(client.address);
            assertEquals("hello-local", call.getData(2, TimeUnit.SECONDS));
            assertTrue(call.isDone());
            assertEquals(1, instances.get());
            assertEquals(0, client.filterLookups.get());
            assertEquals(0, client.localFilters.get());
            assertNull(client.connector());
        }
    }

    @Test
    public void localBusinessFailureDoesNotFallBackToRpcFilters() throws Exception {
        // 本地实现抛出一个可识别的业务异常。
        try (Host client = new Host(10000, "normal")) {
            client.service.setDistributed(false);
            IllegalStateException cause = new IllegalStateException("business failure");
            client.serviceProvider = () -> value -> {
                throw cause;
            };

            // Future 保留原始异常，失败后不再进入 Filter 或网络调用链。
            Throwable failure = failure(client.invoke(client.address));
            assertSame(cause, failure);
            assertEquals(0, client.filterLookups.get());
            assertEquals(0, client.localFilters.get());
            assertNull(client.connector());
        }
    }

    @Test
    public void nullLocalInstanceContinuesThroughRpcFiltersAndNetwork() throws Exception {
        // 调用端的服务工厂本次没有提供本地实例，对端仍发布同一个服务。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();
            client.service.setDistributed(false);
            client.serviceProvider = () -> null;

            // 本地未命中后继续执行 Filter，并通过连接器访问对端。
            assertEquals("hello", client.invoke(server.address).getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.filterLookups.get());
            assertEquals(1, client.localFilters.get());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void remoteFailurePreservesTheMessage() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 对端以明确的错误消息回复当前请求。
            MetFuture call = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload sent = channel.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(sent);
            String message = "schema does not match the service";
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(sent.getRequestID());
            response.setStatus(ProtocolStatus.InvokeError);
            response.addOption("message", message);
            channel.peer.sendData(response).get(2, TimeUnit.SECONDS);

            // 调用方得到保留原始消息的远端异常。
            Throwable error = failure(call);
            assertTrue(error instanceof MetException);
            assertTrue(error.getMessage().contains(message));
        }
    }

    @Test
    public void remoteFailureWithoutMessageFallsBackToStatus() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 对端只返回失败状态，不附带错误消息。
            MetFuture call = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload sent = channel.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(sent);
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(sent.getRequestID());
            response.setStatus(ProtocolStatus.InvokeError);
            channel.peer.sendData(response).get(2, TimeUnit.SECONDS);

            // 缺省错误文本仍包含对端返回的状态码。
            Throwable error = failure(call);
            assertTrue(error instanceof MetException);
            assertTrue(error.getMessage().contains(String.valueOf(ProtocolStatus.InvokeError)));
        }
    }

    @Test
    public void clientWithoutListenerCallsServerThroughConnectorSubscription() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 未开启监听的客户端通过连接器订阅完成普通代理调用。
            MetClient remote = client.client(server.address);
            Echo echo = remote.getRemote(client.service);
            assertEquals("hello", echo.echo("hello"));

            // 调用贯穿双向过滤器并回收超时任务，不创建客户端监听器。
            assertTrue(client.connector().getListenList().isEmpty());
            assertEquals(1, server.remoteFilters.get());
            assertEquals(1, client.localFilters.get());
            assertEquals(1, client.cancelledTimers.get());
            ClassLoader loader = getClass().getClassLoader();
            String hproseClass = "net/hasor/meteor/protocol/hprose/HproseProtocol.class";
            assertNull(loader.getResource(hproseClass));
        }
    }

    @Test
    public void clientWithoutListenerPreservesNullInvocationResult() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 空参数和空返回值同样经由代理与连接器订阅传递。
            MetClient remote = client.client(server.address);
            Echo echo = remote.getRemote(client.service);
            assertNull(echo.echo(null));

            // 空结果也会结束调用、执行过滤器并回收定时任务。
            assertTrue(client.connector().getListenList().isEmpty());
            assertEquals(1, server.remoteFilters.get());
            assertEquals(1, client.localFilters.get());
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void coreInvokesWithoutClientFacadeCalls() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 直接使用核心请求入口，不经过客户端外观。
            Class<?>[] types = { String.class };
            InstanceAddressProvider target = new InstanceAddressProvider(server.address);
            Object[] arguments = { "core" };
            MetRequestObject request = client.rpc.createRequest(target, client.service, "echo", types, arguments);
            MetFuture call = client.rpc.invoke(request);

            // 核心调用完成并且两端过滤器各执行一次。
            assertEquals("core", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.localFilters.get());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void coreDispatchesReverseRequestWithoutClientFacadeCalls() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 先由核心调用建立连接，再让对端沿该连接反向发起请求。
            Class<?>[] types = { String.class };
            InstanceAddressProvider target = new InstanceAddressProvider(server.address);
            Object[] arguments = { "core" };
            MetRequestObject request = client.rpc.createRequest(target, client.service, "echo", types, arguments);
            MetFuture outgoing = client.rpc.invoke(request);
            assertEquals("core", outgoing.getData(2, TimeUnit.SECONDS));

            // 反向分发仍由连接器订阅驱动，无需客户端监听器。
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload reverse = request(client, 42, "reverse");
            channel.peer.sendData(reverse).get(2, TimeUnit.SECONDS);
            assertEquals("reverse", finalResponse(channel).getReturnData());
            assertEquals(1, client.remoteFilters.get());
            assertTrue(client.connector().getListenList().isEmpty());
        }
    }

    @Test
    public void clientFacadeDataCallbackDelegatesToTheSharedCore() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 通过数据回调入口接收共享核心返回的业务值。
            MetClient facade = client.client(server.address);
            BasicFuture<Object> data = new BasicFuture<>();
            FutureCallback<Object> callback = new FutureCallback<Object>() {
                public void completed(Object result) {
                    data.completed(result);
                }

                public void failed(Throwable failure) {
                    data.failed(failure);
                }
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "data" };
            facade.callbackInvoke(client.service, "echo", parameterTypes, arguments, callback);

            // 回调只收到业务值，调用仍经过两端过滤器。
            assertEquals("data", data.get(2, TimeUnit.SECONDS));
            assertEquals(1, client.localFilters.get());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void clientFacadeResponseCallbackDelegatesToTheSharedCore() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 响应回调入口保留完整的响应对象。
            MetClient facade = client.client(server.address);
            BasicFuture<MetResponse> response = new BasicFuture<>();
            FutureCallback<MetResponse> callback = new FutureCallback<MetResponse>() {
                public void completed(MetResponse result) {
                    response.completed(result);
                }

                public void failed(Throwable failure) {
                    response.failed(failure);
                }
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "response" };
            facade.callbackRequest(client.service, "echo", parameterTypes, arguments, callback);

            // 业务值、协议状态和过滤器计数共同验证响应交付。
            MetResponse result = response.get(2, TimeUnit.SECONDS);
            assertEquals("response", result.getData());
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertEquals(1, client.localFilters.get());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void facadeReceivesResponseCompletedBeforeCallbackRegistration() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "instant")) {
            server.bind();

            // 对端在写入过程中同步回复，完成时间早于外观注册回调。
            BasicFuture<Object> result = new BasicFuture<>();
            FutureCallback<Object> callback = new FutureCallback<Object>() {
                public void completed(Object value) {
                    result.completed(value);
                }

                public void failed(Throwable failure) {
                    result.failed(failure);
                }
            };
            Class<?>[] parameterTypes = { String.class };
            MetClient facade = client.client(server.address);
            Object[] arguments = { "value" };
            facade.callbackInvoke(client.service, "echo", parameterTypes, arguments, callback);

            // 已完成的结果仍交付回调，并回收唯一的超时任务。
            assertEquals("reply", result.get(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void facadeReceivesFailureCompletedBeforeCallbackRegistration() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "instant")) {
            server.bind();

            // 无监听地址会在回调注册前立即完成连接失败。
            BasicFuture<Throwable> error = new BasicFuture<>();
            FutureCallback<MetResponse> callback = new FutureCallback<MetResponse>() {
                public void completed(MetResponse value) {
                    fail("Expected connection failure");
                }

                public void failed(Throwable failure) {
                    error.completed(failure);
                }
            };
            InterAddress missingAddress = new InterAddress("memory://missing:1/default");
            MetClient missing = client.client(missingAddress);
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            missing.callbackRequest(client.service, "echo", parameterTypes, arguments, callback);

            // 早于注册的失败仍原样交付，并回收超时任务。
            assertTrue(error.get(2, TimeUnit.SECONDS) instanceof IOException);
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void cancelledWriteReachesTheFacadeFailureCallback() throws Exception {
        // 准备延迟完成的网络写入，回调接口只接收本次调用的终态通知。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "async-write")) {
            server.bind();
            MetClient facade = client.client(server.address);
            BasicFuture<Throwable> failed = new BasicFuture<>();
            FutureCallback<MetResponse> callback = new FutureCallback<MetResponse>() {
                public void completed(MetResponse response) {
                    fail("Expected cancellation failure");
                }

                public void failed(Throwable failure) {
                    failed.completed(failure);
                }
            };

            // 通过连接器取消写入，不能读取 Caller 的内部请求表来取消回调调用。
            Class<?>[] parameterTypes = { String.class };
            facade.callbackRequest(client.service, "echo", parameterTypes, new Object[] { "value" }, callback);
            BasicFuture<MetChannel> writing = client.connector().outgoing.writing;
            assertTrue(writing.cancel());
            assertFalse(writing.cancel());

            // 写入取消作为请求失败交付，并回收请求的定时任务。
            assertTrue(failed.get(2, TimeUnit.SECONDS) instanceof CancellationException);
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void instantResponseCallbackExceptionDoesNotBlockTheNextCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "instant")) {
            server.bind();

            // 用户回调主动抛出异常，记录其实际执行次数。
            AtomicInteger callbacks = new AtomicInteger();
            FutureCallback<MetResponse> callback = new FutureCallback<MetResponse>() {
                public void completed(MetResponse response) {
                    callbacks.incrementAndGet();
                    throw new IllegalArgumentException("callback failed");
                }

                public void failed(Throwable failure) {
                    fail("Expected response");
                }
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            MetClient facade = client.client(server.address);
            facade.callbackRequest(client.service, "echo", parameterTypes, arguments, callback);

            // 已完成响应上的回调异常不会占用下一次调用的容量。
            assertEquals(1, callbacks.get());
            MetFuture next = client.invoke(server.address);
            assertEquals("reply", next.getData(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void delayedResponseCallbackExceptionDoesNotBlockTheNextCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 用户回调主动抛出异常，记录其实际执行次数。
            AtomicInteger callbacks = new AtomicInteger();
            FutureCallback<MetResponse> callback = new FutureCallback<MetResponse>() {
                public void completed(MetResponse response) {
                    callbacks.incrementAndGet();
                    throw new IllegalArgumentException("callback failed");
                }

                public void failed(Throwable failure) {
                    fail("Expected response");
                }
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            MetClient facade = client.client(server.address);
            facade.callbackRequest(client.service, "echo", parameterTypes, arguments, callback);

            // 回调注册后才交付响应，并验证回调异常没有泄漏请求容量。
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload request = channel.sent.remove();
            ResponsePayload response = response(request, ProtocolStatus.OK);
            client.manager.deliver(channel, request.getRequestID(), response);
            assertEquals(1, callbacks.get());
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            assertTrue(next.cancel());
        }
    }

    @Test
    public void unifiedRequestsPreserveDirectionAddressesAndIdsThroughInvocation() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 调用后分别观察出站与入站过滤器接收到的统一请求模型。
            MetFuture call = client.invoke(server.address);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            MetRequest local = client.requests.poll(2, TimeUnit.SECONDS);
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);

            // 两个方向保留相同请求标识及各自的地址、服务和超时。
            assertEquals(MetRequestObject.class, local.getClass());
            assertEquals(local.getClass(), remote.getClass());
            assertSame(call.getRequest(), local);
            assertEquals(local.getRequestID(), remote.getRequestID());
            assertTrue(local.isLocal());
            assertFalse(remote.isLocal());
            assertEquals(client.address, local.getRemoteAddress());
            assertEquals(server.address, local.getTargetAddress());
            assertEquals(client.address, remote.getRemoteAddress());
            assertEquals(server.address, remote.getTargetAddress());
            assertSame(client.service, local.getBindInfo());
            assertSame(server.service, remote.getBindInfo());
            assertEquals("Java", local.getSerializeType());
            assertEquals("Java", remote.getSerializeType());
            assertEquals(10000, local.getTimeout());
            int remoteTimeout = remote.getTimeout();
            assertTrue(remoteTimeout > 0 && remoteTimeout <= local.getTimeout());
            assertFalse(local.isMessage());
            assertFalse(remote.isMessage());
            long before = System.currentTimeMillis();
            assertTrue(local.getReceiveTime() >= before);
        }
    }

    @Test
    public void unifiedRequestModelsReturnDefensiveParameterCopies() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 完成调用，并取得两端过滤器观察到的请求模型。
            MetFuture call = client.invoke(server.address);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            MetRequest local = client.requests.poll(2, TimeUnit.SECONDS);
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(local);
            assertNotNull(remote);

            // 修改参数或类型数组返回值，不应改变任何请求的原始数据。
            local.getParameterObject()[0] = "changed";
            remote.getParameterTypes()[0] = Object.class;
            assertArrayEquals(new Object[] { "hello" }, local.getParameterObject());
            Class<?>[] parameterTypes = { String.class };
            assertArrayEquals(parameterTypes, remote.getParameterTypes());
        }
    }

    @Test
    public void unifiedRequestOptionsAndAttributesStayLocal() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 完成调用，并取得两端过滤器观察到的请求模型。
            MetFuture call = client.invoke(server.address);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            MetRequest local = client.requests.poll(2, TimeUnit.SECONDS);
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(local);
            assertNotNull(remote);

            // 请求选项及属性不会跨方向共享。
            local.addOption("local-only", "first");
            assertNull(remote.getOption("local-only"));
            local.setAttribute("attribute", "local");
            assertNull(remote.getAttribute("attribute"));

            // 后续调用也不继承前次请求的私有选项。
            MetFuture next = client.invoke(server.address);
            assertEquals("hello", next.getData(2, TimeUnit.SECONDS));
            assertNull(next.getRequest().getOption("local-only"));
        }
    }

    @Test
    public void successiveInvocationsAllocateDistinctRequestIds() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 先等待第一次调用完成，释放唯一的请求容量。
            MetFuture first = client.invoke(server.address);
            assertEquals("hello", first.getData(2, TimeUnit.SECONDS));

            // 同一调用端的后续请求必须获得不同标识。
            MetFuture next = client.invoke(server.address);
            assertEquals("hello", next.getData(2, TimeUnit.SECONDS));
            assertNotEquals(first.getRequest().getRequestID(), next.getRequest().getRequestID());
        }
    }

    @Test
    public void inboundRequestKeepsPayloadOptions() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 注入携带协议标记、编码与选项的入站报文。
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            RequestPayload payload = request(server, 42, "metadata");
            payload.setFlags(MetFlags.P2PFlag.addTag((short) 0));
            payload.setSerializeType("wire-coder");
            payload.addOption("trace", "incoming");
            channel.sendData(payload).get(2, TimeUnit.SECONDS);
            assertEquals("metadata", finalResponse(channel.peer).getReturnData());
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertEquals(42, remote.getRequestID());
            assertTrue(remote.isP2PCalls());
            assertEquals("wire-coder", remote.getSerializeType());
            assertEquals(payload.getReceiveTime(), remote.getReceiveTime());
            assertEquals("incoming", remote.getOption("trace"));

            // 协议选项与报文共享，业务属性仍保持私有。
            remote.addOption("trace", "filtered");
            assertEquals("filtered", payload.getOption("trace"));
            payload.addOption("reply", "shared");
            assertEquals("shared", remote.getOption("reply"));
            remote.removeOption("reply");
            assertNull(payload.getOption("reply"));
            remote.setAttribute("trace", "private");
            assertEquals("filtered", payload.getOption("trace"));
        }
    }

    @Test
    public void inboundRequestClampsTimeoutToTheServiceLimit() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 对端声明的期限大于本地服务限制。
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            RequestPayload payload = request(server, 42, "metadata");
            payload.setClientTimeout(20000);
            channel.sendData(payload).get(2, TimeUnit.SECONDS);
            assertEquals("metadata", finalResponse(channel.peer).getReturnData());

            // 入站请求最多采用服务允许的期限。
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertEquals(10000, remote.getTimeout());
        }
    }

    @Test
    public void inboundRequestPreservesTheShorterClientTimeout() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 对端声明的期限小于本地服务限制。
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            RequestPayload payload = request(server, 43, "shorter");
            payload.setClientTimeout(5000);
            channel.sendData(payload).get(2, TimeUnit.SECONDS);
            assertEquals("shorter", finalResponse(channel.peer).getReturnData());

            // 入站请求保留更短的调用方期限。
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertEquals(5000, remote.getTimeout());
        }
    }

    @Test
    public void inboundMessageTypeComesFromPayloadInsteadOfService() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 通过报文显式指定消息模式，服务自身保持普通调用模式。
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            RequestPayload payload = request(server, 44, "message");
            payload.setMessage(true);
            channel.sendData(payload).get(2, TimeUnit.SECONDS);

            // 入站消息模式来自报文标志，并保留对应请求标识。
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertFalse(server.service.isMessage());
            assertTrue(remote.isMessage());
            assertEquals(44, remote.getRequestID());
        }
    }

    @Test
    public void callerWithoutListenerHandlesRequestsOnAnEstablishedChannel() throws Exception {
        try (Host listener = new Host(10000, "normal"); //
             Host peer = new Host(10000, "normal")) {
            listener.bind();

            // 仅建立出站连接，调用端自身不开启监听。
            MemoryChannel outgoing = (MemoryChannel) peer.manager.connect(listener.address).get(2, TimeUnit.SECONDS);
            assertTrue(peer.connector().getListenList().isEmpty());

            // 对端沿既有连接反向请求，Caller 仍能过滤和回复。
            outgoing.peer.sendData(request(peer, 42, "reverse")).get(2, TimeUnit.SECONDS);
            ResponsePayload reply = finalResponse(outgoing);
            assertEquals(42, reply.getRequestID());
            assertEquals("reverse", reply.getReturnData());
            assertEquals(1, peer.remoteFilters.get());
            assertTrue(peer.connector().getListenList().isEmpty());
        }
    }

    @Test
    public void responseDuringSendIsMatchedBeforeWriteFutureCompletes() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "instant")) {
            server.bind();

            // 写入 Future 完成之前收到的响应仍能匹配当前请求。
            assertEquals("reply", client.invoke(server.address).getData(2, TimeUnit.SECONDS));

            // 连续调用证明同步回复也会释放容量并逐次取消定时任务。
            assertEquals("reply", client.invoke(server.address).getData(2, TimeUnit.SECONDS));
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void writeSuccessDoesNotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 保持请求等待响应，观察指定的非终态事件。
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());

            // 当前通道的匹配终态响应才会完成请求并回收超时任务。
            ResponsePayload completed = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID(), completed);
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void responseFromAnotherChannelCannotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 保持请求等待响应，观察指定的非终态事件。
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());

            // 相同请求标识的响应来自另一条连接，必须忽略。
            MetChannel other = client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            ResponsePayload wrongSource = response(request, ProtocolStatus.OK);
            client.manager.deliver(other, request.getRequestID(), wrongSource);
            assertFalse(call.isDone());

            // 当前通道的匹配终态响应才会完成请求并回收超时任务。
            ResponsePayload completed = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID(), completed);
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void failureFromAnotherChannelCannotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 保持请求等待响应，观察指定的非终态事件。
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());

            // 相同请求标识的失败来自另一条连接，必须忽略。
            MetChannel other = client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            ThrowPayload wrongSource = new ThrowPayload(new IOException("wrong source"));
            client.manager.deliver(other, request.getRequestID(), wrongSource);
            assertFalse(call.isDone());

            // 当前通道的匹配终态响应才会完成请求并回收超时任务。
            ResponsePayload completed = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID(), completed);
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void responseWithAnotherRequestIdCannotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 保持请求等待响应，观察指定的非终态事件。
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());

            // 当前连接上的未知请求标识不会结束现有调用。
            ResponsePayload unknown = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID() + 1, unknown);
            assertFalse(call.isDone());

            // 当前通道的匹配终态响应才会完成请求并回收超时任务。
            ResponsePayload completed = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID(), completed);
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void acceptResponseDoesNotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 保持请求等待响应，观察指定的非终态事件。
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());

            // 接受状态只表示对端接收成功，不是最终业务结果。
            ResponsePayload accepted = response(request, ProtocolStatus.Accept);
            client.manager.deliver(source, request.getRequestID(), accepted);
            assertFalse(call.isDone());

            // 当前通道的匹配终态响应才会完成请求并回收超时任务。
            ResponsePayload completed = response(request, ProtocolStatus.OK);
            client.manager.deliver(source, request.getRequestID(), completed);
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void correctSourceFailurePreservesCauseAndReleasesCapacity() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 从请求所属通道交付带嵌套原因的失败。
            MetFuture first = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            IOException failure = new IOException("connection lost", new IllegalArgumentException("original cause"));
            client.manager.deliver(source, first.getRequest().getRequestID(), new ThrowPayload(failure));
            assertSame(failure, failure(first));

            // 保留异常对象后释放请求容量，后续调用仍可等待。
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void responseWithoutSourceCannotCompleteAnUnattachedRequest() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 延迟交付连接，使请求尚未关联任何通道。
            MetFuture call = client.invoke(server.address);
            long requestId = call.getRequest().getRequestID();
            MemoryConnector connector = client.connector();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(requestId);
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("too early");
            client.manager.deliver(null, requestId, response);
            assertFalse(call.isDone());

            // 连接交付之后，正常响应才能完成调用。
            connector.connecting.completed(connector.outgoing);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void failureWithoutSourceCannotCompleteAnUnattachedRequest() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 延迟交付连接，使请求尚未关联任何通道。
            MetFuture call = client.invoke(server.address);
            long requestId = call.getRequest().getRequestID();
            MemoryConnector connector = client.connector();
            ThrowPayload failure = new ThrowPayload(new IOException("no source"));
            client.manager.deliver(null, requestId, failure);
            assertFalse(call.isDone());

            // 连接交付之后，正常响应才能完成调用。
            connector.connecting.completed(connector.outgoing);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void responseBeforeChannelAttachmentCannotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 延迟交付连接，使请求尚未关联任何通道。
            MetFuture call = client.invoke(server.address);
            long requestId = call.getRequest().getRequestID();
            MemoryConnector connector = client.connector();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(requestId);
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("too early");
            client.manager.deliver(connector.outgoing, requestId, response);
            assertFalse(call.isDone());

            // 连接交付之后，正常响应才能完成调用。
            connector.connecting.completed(connector.outgoing);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void failureBeforeChannelAttachmentCannotCompleteTheCall() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 延迟交付连接，使请求尚未关联任何通道。
            MetFuture call = client.invoke(server.address);
            long requestId = call.getRequest().getRequestID();
            MemoryConnector connector = client.connector();
            ThrowPayload failure = new ThrowPayload(new IOException("not attached"));
            client.manager.deliver(connector.outgoing, requestId, failure);
            assertFalse(call.isDone());

            // 连接交付之后，正常响应才能完成调用。
            connector.connecting.completed(connector.outgoing);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void expiredCallIsNotSentWhenConnectionArrivesLate() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(100, "delayed")) {
            server.bind();

            // 请求先于连接交付超时。
            MetFuture expired = client.invoke(server.address);
            assertTrue(failure(expired) instanceof MetTimeoutException);
            MemoryConnector connector = client.connector();
            MemoryChannel channel = connector.outgoing;

            // 迟到连接不会发送已经超时的请求。
            connector.connecting.completed(channel);
            assertTrue(channel.sent.isEmpty());
        }
    }

    @Test
    public void cancelledCallIsNotSentWhenConnectionArrivesLate() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(100, "delayed")) {
            server.bind();

            // 请求在连接交付前由调用方取消。
            MetFuture cancelled = client.invoke(server.address);
            MemoryConnector connector = client.connector();
            MemoryChannel channel = connector.outgoing;
            assertTrue(cancelled.cancel());

            // 迟到连接保留可用状态，但不会发送已取消的请求。
            connector.connecting.completed(channel);
            assertTrue(channel.sent.isEmpty());
            assertTrue(channel.isActive());
        }
    }

    @Test
    public void explicitRequestTimeoutOverridesTheServiceTimeout() throws Exception {
        // 服务默认很短，单次请求显式延长；连接器暂不返回响应。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(100, "hold")) {
            server.bind();
            AddressProvider address = new InstanceAddressProvider(server.address);
            Class<?>[] types = { String.class };
            Object[] arguments = { "hello" };
            MetRequestObject request = client.rpc.createRequest(address, client.service, "echo", types, arguments);
            request.setTimeout(2000);
            MetFuture invocation = client.rpc.invoke(request);

            // 超过服务默认值后请求仍在等待，最终显式取消可正常回收。
            RequestPayload payload = client.connector().outgoing.sent.poll(1, TimeUnit.SECONDS);
            assertNotNull(payload);
            assertThrows(TimeoutException.class, () -> invocation.getData(300, TimeUnit.MILLISECONDS));
            assertFalse(invocation.isDone());
            assertTrue(invocation.cancel());
            assertTrue(payload.completion().isDone());
        }
    }

    @Test
    public void timeoutCompletesProtocolLifecycleAfterRequestWasSent() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(200, "hold")) {
            server.bind();

            // 请求已写入通道，但对端持续保持未响应状态。
            MetFuture call = client.invoke(server.address);
            RequestPayload payload = client.connector().outgoing.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(payload);

            // 超时同时结束业务 Future 和协议报文，保留通道可用性。
            Throwable cause = failure(call);
            assertTrue(cause instanceof MetTimeoutException);
            assertTrue(payload.completion().isDone());
            assertSame(cause, payload.completion().getCause());
            assertTrue(client.connector().outgoing.isActive());
        }
    }

    @Test
    public void cancelledCallDoesNotCloseItsChannel() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 发送等待中的调用，再由调用方主动取消。
            MetFuture call = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            assertTrue(call.cancel());
            assertTrue(channel.isActive());
            assertEquals(1, client.cancelledTimers.get());

            // 取消结束协议报文，迟到响应不能覆盖取消终态。
            RequestPayload request = channel.sent.poll(2, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            assertTrue(request.completion().getCause() instanceof CancellationException);
            client.manager.deliver(channel, request.getRequestID(), response(request, ProtocolStatus.OK));
            assertTrue(call.isCancelled());

            // 同一调用端仍有容量接收下一次请求。
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
        }
    }

    @Test
    public void connectFailureReleasesRequestCapacity() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 首次连接失败后，唯一的请求槽位应立即释放。
            InterAddress missing = new InterAddress("memory", "missing", 1, "default");
            MetFuture missingCall = client.invoke(missing);
            assertTrue(failure(missingCall) instanceof IOException);
            assertEquals(1, client.cancelledTimers.get());

            // 后续正常调用成功，证明连接失败没有占住请求容量。
            MetFuture next = client.invoke(server.address);
            assertEquals("hello", next.getData(2, TimeUnit.SECONDS));
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void writeFailureReleasesRequestCapacity() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "fail-write")) {
            server.bind();

            // 两次调用都进入写入失败路径，后一次不能被容量限制提前拒绝。
            MetFuture first = client.invoke(server.address);
            assertTrue(failure(first) instanceof IOException);
            MetFuture next = client.invoke(server.address);
            assertTrue(failure(next) instanceof IOException);

            // 每次写入失败都回收自己的超时任务。
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void unknownSchemeFailsThroughRpcFuture() throws Exception {
        // 未注册的地址协议通过调用 Future 暴露失败。
        try (Host client = new Host(10000, "normal")) {
            InterAddress unknown = new InterAddress("unknown", "localhost", 1, "default");
            Throwable failure = failure(client.invoke(unknown));

            // 协议选择在登记请求前失败，不创建连接器和超时任务。
            assertTrue(failure instanceof MetException);
            assertNull(client.connector());
            assertEquals(0, client.cancelledTimers.get());
        }
    }

    @Test
    public void ownerCloseStopsListenersThenDrainsWritesBeforeFailingPendingCalls() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 同时准备监听器和未完成调用，并用闸门阻塞通道排空。
            MemoryListen listen = client.bind();
            MetClient remote = client.client(server.address);
            MetFuture pending = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            channel.drainGate = new BasicFuture<>();

            // 异步关闭应先停止监听与接收新调用，再等待已有写入排空。
            FutureTask<Void> closing = new FutureTask<>(client::close, null);
            Thread thread = new Thread(closing, "rpc-close-test");
            thread.start();
            try {
                assertTrue(channel.draining.await(2, TimeUnit.SECONDS));
                assertFalse(listen.isActive());
                assertFalse(closing.isDone());
                assertFalse(pending.isDone());
                Class<?>[] parameterTypes = { String.class };
                MetFuture late = remote.asyncInvoke(client.service, "echo", parameterTypes, new Object[] { "late" });
                assertTrue(failure(late) instanceof IllegalStateException);
            } finally {
                // 无论断言是否成功，都释放关闭线程并有界等待其退出。
                channel.close();
                channel.drainGate.completed(channel);
                closing.get(3, TimeUnit.SECONDS);
            }

            // 排空之后结束未完成请求；重复关闭不会重新初始化管理器。
            assertTrue(failure(pending) instanceof IllegalStateException);
            assertFalse(channel.isActive());
            client.close();
            assertFalse(client.manager.isInitialized());
        }
    }

    @Test
    public void inboundRequestsAreIndependentOfOutboundCapacity() throws Exception {
        try (Host server = new Host(10000, "hold"); //
             Host peer = new Host(10000, "normal")) {
            server.bind();
            peer.bind();

            // 占满服务端唯一的出站请求槽位，确认继续出站会被拒绝。
            MetFuture outbound = server.invoke(peer.address);
            assertFalse(outbound.isDone());
            MetFuture rejected = server.invoke(peer.address);
            assertTrue(failure(rejected) instanceof MetException);

            // 入站分发不受出站容量影响，原出站请求仍在等待。
            MetFuture inbound = peer.invoke(server.address);
            assertEquals("hello", inbound.getData(2, TimeUnit.SECONDS));
            assertEquals(1, server.remoteFilters.get());
            assertFalse(outbound.isDone());
            outbound.cancel();
        }
    }

    @Test
    public void sameInboundRequestIdRepliesOnItsOwnChannel() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host first = new Host(10000, "normal"); //
             Host second = new Host(10000, "normal")) {
            server.bind();

            // 两个独立连接分别发送相同标识、不同参数的请求。
            MemoryChannel firstChannel = (MemoryChannel) first.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            Future<MetChannel> secondConnection = second.manager.connect(server.address);
            MemoryChannel secondChannel = (MemoryChannel) secondConnection.get(2, TimeUnit.SECONDS);
            firstChannel.sendData(request(server, 42, "first")).get(2, TimeUnit.SECONDS);
            secondChannel.sendData(request(server, 42, "second")).get(2, TimeUnit.SECONDS);

            // 相同标识的响应必须回到各自的连接，不得串线。
            ResponsePayload firstReply = finalResponse(firstChannel.peer);
            ResponsePayload secondReply = finalResponse(secondChannel.peer);
            assertEquals(42, firstReply.getRequestID());
            assertEquals(42, secondReply.getRequestID());
            assertEquals("first", firstReply.getReturnData());
            assertEquals("second", secondReply.getReturnData());
            assertEquals(2, server.remoteFilters.get());
        }
    }

    @Test
    public void mismatchedInboundRequestIdDoesNotInvokeService() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 注入外部标识与报文标识不一致的入站请求。
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get(2, TimeUnit.SECONDS);
            server.manager.deliver(channel.peer, 43, request(server, 42, "invalid"));

            // 随后正常请求仍可完成，只有有效请求经过服务过滤器。
            channel.sendData(request(server, 44, "valid")).get(2, TimeUnit.SECONDS);
            ResponsePayload response = finalResponse(channel.peer);
            assertEquals(44, response.getRequestID());
            assertEquals("valid", response.getReturnData());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void responseStillCompletesDuringCloseDrain() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 先建立等待响应的调用，再阻塞通道的关闭排空阶段。
            MetFuture pending = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload request = channel.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            channel.drainGate = new BasicFuture<>();

            // 关闭尚未完成时交付最终响应，等待中的调用仍能成功。
            FutureTask<Void> closing = new FutureTask<>(client::close, null);
            Thread thread = new Thread(closing, "rpc-response-during-close");
            thread.start();
            try {
                assertTrue(channel.draining.await(2, TimeUnit.SECONDS));
                client.manager.deliver(channel, request.getRequestID(), response(request, ProtocolStatus.OK));
                assertEquals("reply", pending.getData(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
            } finally {
                // 释放排空闸门，确保关闭线程在有界时间内结束。
                channel.close();
                channel.drainGate.completed(channel);
                closing.get(3, TimeUnit.SECONDS);
            }

            // 关闭结束后仍保留成功结果，且只回收一次定时任务。
            assertEquals("reply", pending.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    private static RequestPayload request(Host server, long requestId, String value) {
        // 构造符合服务描述的对端请求报文，交付动作保留在当前测试。
        RequestPayload request = new RequestPayload();
        request.setRequestID(requestId);
        request.setServiceGroup(server.service.getBindGroup());
        request.setServiceName(server.service.getBindName());
        request.setServiceVersion(server.service.getBindVersion());
        request.setSerializeType(server.service.getSerializeType());
        request.setClientTimeout(10000);
        request.setReceiveTime(System.currentTimeMillis());

        // 业务方法和参数按协议报文契约编码。
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), value);
        return request;
    }

    private static ResponsePayload finalResponse(MemoryChannel channel) throws Exception {
        // 入站分发可能先返回 Accept，只跳过这一条非终态消息。
        ResponsePayload response = channel.replies.poll(2, TimeUnit.SECONDS);
        assertNotNull(response);
        if (response.getStatus() == ProtocolStatus.Accept) {
            response = channel.replies.poll(2, TimeUnit.SECONDS);
            assertNotNull(response);
        }

        // 有界读取最终响应，防止错误状态在调用处被业务值断言掩盖。
        assertEquals(ProtocolStatus.OK, response.getStatus());
        return response;
    }

    @Test
    public void constructorRequiresInitializedManager() throws Exception {
        // 保留一个尚未初始化的管理器作为构造输入。
        try (Host host = new Host(10000, "normal")) {
            MetContext context = host.manager.context();
            try (ConnectorManager uninitialized = new ConnectorManager(context)) {
                // Caller 必须拒绝未初始化的管理器，并说明初始化要求。
                IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
                    new MetCaller(uninitialized, id -> new Supplier[0]);
                });
                assertTrue(error.getMessage().contains("Initialize"));
            }
        }
    }

    @Test
    public void cancelledConnectFailsRpcAndCancelsItsTimeout() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 连接尚未交付时取消连接 Future。
            MetFuture call = client.invoke(server.address);
            client.connector().connecting.cancel();
            assertTrue(failure(call) instanceof CancellationException);
            assertEquals(1, client.cancelledTimers.get());

            // 连接取消回收请求容量，后续调用仍可进入等待。
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
        }
    }

    @Test
    public void cancelledAsyncWriteReleasesCapacityWithoutClosingChannel() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "async-write")) {
            server.bind();

            // 取消异步写入，使当前请求进入失败终态。
            MetFuture cancelled = client.invoke(server.address);
            MemoryChannel first = client.connector().outgoing;
            first.writing.cancel();
            assertTrue(failure(cancelled) instanceof CancellationException);
            assertTrue(first.isActive());
            assertEquals(1, client.cancelledTimers.get());

            // 下一次调用可进入等待状态，证明容量已释放。
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            assertTrue(next.cancel());
        }
    }

    @Test
    public void failedAsyncWriteReleasesCapacityWithoutClosingChannel() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "async-write")) {
            server.bind();

            // 对端异步报告写入失败，调用方应收到同一个异常对象。
            MetFuture failed = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            IOException error = new IOException("asynchronous write failure");
            channel.writing.failed(error);
            assertSame(error, failure(failed));
            assertTrue(channel.isActive());
            assertEquals(1, client.cancelledTimers.get());

            // 写入失败没有占用后续调用的容量。
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            assertTrue(next.cancel());
        }
    }

    @Test
    public void synchronousChannelExceptionBecomesRpcFailure() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "throw-write")) {
            server.bind();

            // 通道同步抛出的异常应转为调用失败。
            MetFuture first = client.invoke(server.address);
            assertTrue(failure(first) instanceof IllegalStateException);

            // 再次调用仍到达写入路径，证明失败没有泄漏请求容量。
            MetFuture next = client.invoke(server.address);
            assertTrue(failure(next) instanceof IllegalStateException);
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void closedCallerRejectsLocalRequestsBeforeFilters() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 关闭有待完成调用的核心，再次关闭保持幂等。
            MetFuture active = client.invoke(server.address);
            client.rpc.close();
            client.rpc.close();
            assertTrue(failure(active) instanceof IllegalStateException);

            // 新建的本地请求在进入过滤器之前就被拒绝。
            Class<?>[] types = { String.class };
            InstanceAddressProvider target = new InstanceAddressProvider(server.address);
            Object[] arguments = { "rejected" };
            MetRequestObject rejected = client.rpc.createRequest(target, client.service, "echo", types, arguments);
            MetFuture rejectedCall = client.rpc.invoke(rejected);
            assertTrue(failure(rejectedCall) instanceof IllegalStateException);
            assertEquals(1, client.localFilters.get());
            assertEquals(0, client.remoteFilters.get());
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void closedCallerRejectsInboundRequestsBeforeFilters() throws Exception {
        // 准备独立的服务端和调用端内存连接器。
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "hold")) {
            server.bind();

            // 记录已建立通道，再关闭调用核心。
            MetFuture active = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload request = channel.sent.remove();
            client.rpc.close();
            client.rpc.close();
            assertTrue(failure(active) instanceof IllegalStateException);

            // 关闭后从旧通道到达的入站请求不执行过滤器，也不生成响应。
            client.manager.deliver(channel, request.getRequestID(), request);
            assertEquals(1, client.localFilters.get());
            assertEquals(0, client.remoteFilters.get());
            assertTrue(channel.replies.isEmpty());
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void connectionCompletingAfterCloseCannotSendTheRequest() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "delayed")) {
            server.bind();

            // 连接保持未交付状态时关闭 Caller。
            MetFuture call = client.invoke(server.address);
            MemoryConnector connector = client.connector();
            client.rpc.close();

            // 关闭后交付的迟到连接不能发送旧请求。
            connector.connecting.completed(connector.outgoing);
            assertTrue(failure(call) instanceof IllegalStateException);
            assertTrue(connector.outgoing.sent.isEmpty());
        }
    }

    @Test
    public void subscriptionFailureDoesNotCloseTheProvidedManager() throws Exception {
        // 管理器已初始化，但在 Caller 订阅时主动报告失败。
        try (Host host = new Host(10000, "normal"); //
             ConnectorManager manager = new ConnectorManager(host.manager.context()) {
                 @Override
                 public void subscribe(ConnectorSubscriber subscriber) {
                     throw new IllegalStateException("subscription failed");
                 }
             }) {
            host.configured.set(Collections.emptyList());
            manager.init();
            host.configured.set(Collections.singletonList(host.config));

            // 构造失败原样传播，外部提供的管理器仍归调用方管理。
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
                new MetCaller(manager, id -> new Supplier[0]);
            });
            assertEquals("subscription failed", error.getMessage());
            assertTrue(manager.isInitialized());
        }
    }

    @Test
    public void callerCloseDoesNotCloseTheProvidedManager() throws Exception {
        // Caller 借用由外层创建和持有的连接管理器。
        try (Host host = new Host(10000, "normal")) {
            host.rpc.close();

            // 关闭 Caller 只释放 RPC 自有资源，连接管理器仍由 Host 负责关闭。
            assertTrue(host.manager.isInitialized());
        }
    }

    @Test
    public void requestCreationUsesTheDistributedAddressProvider() throws Exception {
        // 分布式提供者读取路由信息，并返回本次请求的目标地址。
        try (Host host = new Host(10000, "normal")) {
            Object[] arguments = { "value" };
            AddressProvider provider = new AddressProvider() {
                public boolean isDistributed() {
                    return true;
                }

                public InterAddress get(RouteContext input) {
                    // 观察真实路由输入，不自行模拟请求创建逻辑。
                    String serviceId = input.getServiceId();
                    String methodName = (String) input.getMethod().get("name");
                    Object[] args = input.getArgs();
                    assertEquals(host.service.getBindID(), serviceId);
                    assertEquals("echo", methodName);
                    assertSame(arguments, args);
                    return host.address;
                }
            };
            Class<?>[] types = { String.class };
            MetRequestObject request = host.rpc.createRequest(provider, host.service, "echo", types, arguments);

            // 只创建请求不会连接，分布式调用不会附加 P2P 标志。
            assertFalse(request.isP2PCalls());
            assertEquals(host.address, request.getTargetAddress());
            assertNull(host.connector());
        }
    }

    @Test
    public void requestCreationAllocatesDistinctIds() throws Exception {
        // 对同一服务连续创建请求，但不触发网络调用。
        try (Host host = new Host(10000, "normal")) {
            AddressProvider provider = new AddressProvider() {
                public boolean isDistributed() {
                    return true;
                }

                public InterAddress get(RouteContext input) {
                    return host.address;
                }
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject first = host.rpc.createRequest(provider, host.service, "echo", parameterTypes, arguments);
            MetRequestObject second = host.rpc.createRequest(provider, host.service, "echo", parameterTypes, arguments);

            // 每次创建都分配独立标识，且不创建连接器。
            assertNotEquals(first.getRequestID(), second.getRequestID());
            assertNull(host.connector());
        }
    }

    @Test
    public void requestCreationMarksFixedAddressAsP2P() throws Exception {
        // 固定实例地址表达点对点请求创建。
        try (Host host = new Host(10000, "normal")) {
            InstanceAddressProvider target = new InstanceAddressProvider(host.address);
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject request = host.rpc.createRequest(target, host.service, "echo", parameterTypes, arguments);

            // 点对点标志与目标地址随请求保留，创建过程不连接。
            assertTrue(request.isP2PCalls());
            assertEquals(host.address, request.getTargetAddress());
            assertNull(host.connector());
        }
    }

    @Test
    public void requestCreationCopiesDefaultsWithoutSharingLaterChanges() throws Exception {
        // 创建请求时复制默认选项，同时保留值为空的选项键。
        try (Host host = new Host(10000, "normal")) {
            host.requestOptions.addOption("trace", "initial");
            host.requestOptions.addOption("nullable", null);
            InstanceAddressProvider target = new InstanceAddressProvider(host.address);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject first = host.rpc.createRequest(target, host.service, "echo", types, arguments);
            assertEquals("initial", first.getOption("trace"));
            assertTrue(Arrays.asList(first.getOptionKeys()).contains("nullable"));
            assertNull(first.getOption("nullable"));

            // 默认值的后续修改只影响新创建的请求。
            host.requestOptions.addOption("trace", "updated");
            host.requestOptions.removeOption("nullable");
            MetRequestObject next = host.rpc.createRequest(target, host.service, "echo", types, arguments);
            assertEquals("initial", first.getOption("trace"));
            assertEquals("updated", next.getOption("trace"));
            assertFalse(Arrays.asList(next.getOptionKeys()).contains("nullable"));

            // 请求自己的修改不会写回默认值或影响另一个请求。
            first.addOption("trace", "custom");
            assertEquals("updated", host.requestOptions.getOption("trace"));
            assertEquals("updated", next.getOption("trace"));
        }
    }

    @Test
    public void invokePreservesOverridesAndRemovalsWithoutReloadingDefaults() throws Exception {
        try (Host server = new Host(10000, "normal"); //
             Host client = new Host(10000, "normal")) {
            server.bind();

            // 先复制默认值，再由单次请求覆盖和删除选项。
            client.requestOptions.addOption("override", "default");
            client.requestOptions.addOption("remove", "default");
            client.requestOptions.addOption("version", "initial");
            InstanceAddressProvider target = new InstanceAddressProvider(server.address);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject request = client.rpc.createRequest(target, client.service, "echo", types, arguments);
            request.addOption("override", "custom");
            request.removeOption("remove");

            // 创建后修改默认值，调用仍须使用请求已经选定的快照。
            client.requestOptions.addOption("version", "updated");
            client.requestOptions.addOption("later", "added-after-creation");
            MetFuture call = client.rpc.invoke(request);
            assertEquals("value", call.getData(3, TimeUnit.SECONDS));

            // 出站过滤器与入站请求看到的都是调用方最终选定的选项。
            MetRequest outgoing = client.requests.poll(2, TimeUnit.SECONDS);
            MetRequest incoming = server.requests.poll(2, TimeUnit.SECONDS);
            assertSame(request, outgoing);
            assertNotNull(incoming);
            for (MetRequest observed : new MetRequest[] { outgoing, incoming }) {
                assertEquals("custom", observed.getOption("override"));
                assertEquals("initial", observed.getOption("version"));
                List<String> keys = Arrays.asList(observed.getOptionKeys());
                assertFalse(keys.contains("remove"));
                assertFalse(keys.contains("later"));
            }
            assertEquals("default", client.requestOptions.getOption("remove"));
        }
    }

    private static ResponsePayload response(RequestPayload request, short status) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(status);
        response.setReturnData("reply");
        return response;
    }

    private static Throwable failure(MetFuture call) throws Exception {
        try {
            call.getData(3, TimeUnit.SECONDS);
            throw new AssertionError("Expected RPC failure");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static final class Host implements AutoCloseable {
        private static final AtomicInteger                                IDS             = new AtomicInteger();
        private final        InterAddress                                 address;
        private final        ServiceDomain<Echo>                          service;
        private final        AtomicInteger                                localFilters    = new AtomicInteger();
        private final        AtomicInteger                                remoteFilters   = new AtomicInteger();
        private final        AtomicInteger                                filterLookups   = new AtomicInteger();
        private final        AtomicInteger                                cancelledTimers = new AtomicInteger();
        private final        BlockingQueue<MetRequest>                    requests        = new LinkedBlockingQueue<>();
        private final        OptionInfo                                   requestOptions  = new OptionInfo();
        private final        ConnectorConfig                              config;
        private final        AtomicReference<Collection<ConnectorConfig>> configured;
        private final        MemoryConnector                              manager;
        private final        MetCaller                                    rpc;
        private volatile     Supplier<Echo>                               serviceProvider;

        @SuppressWarnings("unchecked")
        private Host(int timeout, String mode) {
            // 配置独立地址、服务元信息和内存连接器故障模式。
            int port = IDS.incrementAndGet();
            this.address = new InterAddress("memory", "localhost", port, "default");
            this.service = new ServiceDomain<>(Echo.class);
            this.service.setBindName("Echo");
            this.service.setServiceType(MetServiceType.Provider);
            this.service.setSerializeType("Java");
            this.service.setClientTimeout(timeout);
            this.service.setDistributed(true);
            Map<String, String> options = new HashMap<>();
            options.put("mode", mode);
            ProtocolConfig mount = ProtocolConfig.newConfig("rpc", this.address.getSchema(), options);
            this.config = new ConnectorConfig("rpc", this.address, options, Collections.singletonList(mount), true);
            this.configured = new AtomicReference<>(Collections.emptyList());

            // 只为 Caller 所需的设置契约提供固定值。
            MetSettings settings = proxy(MetSettings.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getConnectorConfigs":
                        return this.configured.get();
                    case "getDefaultProtocol":
                        return "rpc";
                    case "getQueueMaxSize":
                        return 16;
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getDefaultTimeout":
                        return timeout;
                    case "getMaximumRequest":
                        return 1;
                    case "getSendLimitPolicy":
                        return SendLimitPolicy.Reject;
                    case "getUnitName":
                        return "default";
                    case "getRequestOptions":
                        return this.requestOptions;
                    case "getResponseOptions":
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + method);
                }
            });

            // 上下文只提供服务、序列化器与地址查询，不模拟 Caller 行为。
            Echo echo = value -> value;
            this.serviceProvider = () -> echo;
            JavaSerializeCoder coder = new JavaSerializeCoder();
            MetContext context = proxy(MetContext.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getApplicationName":
                        return "application";
                    case "getDefaultProtocol":
                        return "rpc";
                    case "getSettings":
                        return settings;
                    case "getSerializeCoder":
                        return coder;
                    case "getClassLoader":
                        return getClass().getClassLoader();
                    case "subscribe":
                        return (Cancellable) () -> true;
                    case "getServiceInfo":
                        return this.service;
                    case "getServiceProvider":
                        return this.serviceProvider;
                    default:
                        throw new AssertionError("Unexpected context: " + method);
                }
            });

            // 使用真实管理器与调度器，只观察超时任务取消次数。
            this.manager = new MemoryConnector(context, this.config) {
                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    Cancellable timer = super.schedule(task, delay);
                    return () -> {
                        Host.this.cancelledTimers.incrementAndGet();
                        return timer.cancel();
                    };
                }
            };
            this.manager.init();
            this.configured.set(Collections.singletonList(this.config));

            // 双向过滤器观察真实请求，再继续执行正式分发链。
            MetFilter filter = (request, response, chain) -> {
                this.requests.add(request);
                if (request.isLocal()) {
                    this.localFilters.incrementAndGet();
                } else {
                    this.remoteFilters.incrementAndGet();
                }
                chain.doFilter(request, response);
            };
            Supplier<MetFilter> filterSupplier = () -> filter;
            MetFilterProvider filters = id -> {
                this.filterLookups.incrementAndGet();
                return new Supplier[] { filterSupplier };
            };
            this.rpc = new MetCaller(this.manager, filters);
        }

        private MemoryListen bind() {
            return this.manager.bindMemory();
        }

        private MemoryConnector connector() {
            return this.manager.isCreated() ? this.manager : null;
        }

        private MetClient client(InterAddress target) {
            return new MetClientImpl(this.rpc, new InstanceAddressProvider(target));
        }

        private MetFuture invoke(InterAddress target) {
            MetClient client = this.client(target);
            Class<?>[] types = { String.class };
            return client.asyncInvoke(this.service, "echo", types, new Object[] { "hello" });
        }

        @Override
        public void close() {
            this.rpc.stopAdmission();
            try {
                this.manager.close();
            } finally {
                this.rpc.close();
            }
        }
    }

    private static class MemoryConnector extends ConnectorManager {
        private static final Map<InterAddress, MemoryConnector> LISTENERS = new ConcurrentHashMap<>();
        private final        ConnectorConfig                    config;
        private final        List<ConnectorSubscriber>          receivers = new CopyOnWriteArrayList<>();
        private final        Set<MemoryChannel>                 channels  = ConcurrentHashMap.newKeySet();
        private volatile     MemoryChannel                      outgoing;
        private volatile     BasicFuture<MetChannel>            connecting;
        private volatile     MemoryListen                       listen;
        private volatile     boolean                            created;

        private MemoryConnector(MetContext context, ConnectorConfig config) {
            super(context);
            this.config = config;
        }

        @Override
        public void subscribe(ConnectorSubscriber subscriber) {
            super.subscribe(subscriber);
            this.receivers.add(subscriber);
        }

        private void deliver(MetChannel channel, long requestId, Payload payload) {
            for (ConnectorSubscriber receiver : this.receivers) {
                receiver.onMessage(channel, requestId, payload);
            }
        }

        private InterAddress localAddress() {
            return this.config.address();
        }

        @Override
        public InterAddress getListenAddress(//
                String endpointName, String protocolName, String addressScheme) {
            if (!StringUtils.equals(this.config.name(), endpointName)) {
                return null;
            }
            for (ProtocolConfig protocol : this.config.protocols()) {
                boolean protocolMatched = StringUtils.equals(protocol.name(), protocolName);
                boolean schemeMatched = StringUtils.equals(protocol.scheme(), addressScheme);
                if (protocolMatched && schemeMatched) {
                    InterAddress configured = this.config.address();
                    return new InterAddress(//
                            protocol.scheme(), configured.getHost(), configured.getPort(), configured.getFormUnit());
                }
            }
            return null;
        }

        private ConnectorConfig config() {
            return this.config;
        }

        private boolean isCreated() {
            return this.created;
        }

        private Collection<MemoryListen> getListenList() {
            MemoryListen current = this.listen;
            if (current == null || !current.isActive()) {
                return Collections.emptyList();
            }
            return Collections.singletonList(current);
        }

        private MemoryListen bindMemory() {
            this.created = true;
            MemoryListen current = this.listen;
            if (current == null || !current.isActive()) {
                current = new MemoryListen(this, this.localAddress());
                this.listen = current;
            }
            current.start();
            return current;
        }

        @Override
        public Future<MetChannel> connect(InterAddress target) {
            return this.connect(target, null);
        }

        @Override
        public Future<MetChannel> connect(InterAddress target, String protocol) {
            return this.connect(this.config.name(), protocol, target);
        }

        @Override
        public Future<MetChannel> connect(String endpointName, String protocol, InterAddress target) {
            // 无监听对端时直接交付连接失败。
            this.connecting = new BasicFuture<>();
            if (!this.isInitialized()) {
                this.connecting.failed(new IllegalStateException("Manager is not initialized"));
                return this.connecting;
            }
            if (!StringUtils.equalsIgnoreCase(this.config.address().getSchema(), target.getSchema())) {
                String message = "No connector configured for " + target.getSchema();
                this.connecting.failed(new MetException(ProtocolStatus.ProtocolUndefined, message));
                return this.connecting;
            }

            MemoryConnector server = LISTENERS.get(target);
            if (server == null) {
                this.connecting.failed(new IOException("No listener"));
                return this.connecting;
            }

            // 为两端创建成对通道，消息统一交给各自管理器的订阅器。
            this.created = true;
            this.outgoing = new MemoryChannel(this, target);
            MemoryChannel incoming = new MemoryChannel(server, this.localAddress());
            this.outgoing.peer = incoming;
            incoming.peer = this.outgoing;
            this.channels.add(this.outgoing);
            server.channels.add(incoming);

            // 延迟模式由测试控制连接何时交付。
            String mode = this.config().options().getOrDefault("mode", "normal");
            if (!StringUtils.equals("delayed", mode)) {
                this.connecting.completed(this.outgoing);
            }
            return this.connecting;
        }

        private void closed(MemoryChannel channel) {
            this.channels.remove(channel);
        }

        @Override
        public void close() {
            MemoryListen current = this.listen;
            if (current != null) {
                current.close();
            }

            List<Future<MetChannel>> draining = new ArrayList<>();
            for (MemoryChannel channel : new ArrayList<>(this.channels)) {
                draining.add(channel.drainAndClose());
            }
            for (Future<MetChannel> future : draining) {
                try {
                    future.get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // 通道已经结束，管理器仍需继续回收其它资源。
                }
            }

            this.receivers.clear();
            super.close();
        }
    }

    private static final class MemoryListen {
        private final MemoryConnector owner;
        private final InterAddress    address;
        private       boolean         active;

        private MemoryListen(MemoryConnector owner, InterAddress address) {
            this.owner = owner;
            this.address = address;
        }

        private synchronized boolean isActive() {
            return this.active;
        }

        private synchronized void start() {
            this.active = true;
            MemoryConnector.LISTENERS.put(this.address, this.owner);
        }

        private synchronized void close() {
            this.active = false;
            MemoryConnector.LISTENERS.remove(this.address, this.owner);
        }
    }

    private static final class MemoryChannel implements MetChannel {
        private final    MemoryConnector                owner;
        private final    InterAddress                   remote;
        private final    BlockingQueue<RequestPayload>  sent     = new LinkedBlockingQueue<>();
        private final    BlockingQueue<ResponsePayload> replies  = new LinkedBlockingQueue<>();
        private final    CountDownLatch                 draining = new CountDownLatch(1);
        private volatile boolean                        active   = true;
        private          MemoryChannel                  peer;
        private          BasicFuture<MetChannel>        drainGate;
        private          BasicFuture<MetChannel>        writing;

        private MemoryChannel(MemoryConnector owner, InterAddress remote) {
            this.owner = owner;
            this.remote = remote;
        }

        public String getProtocol() {
            return "rpc";
        }

        public InterAddress getLocal() {
            return this.owner.localAddress();
        }

        public InterAddress getRemote() {
            return this.remote;
        }

        public boolean isActive() {
            return this.active;
        }

        public Future<MetChannel> sendData(Payload payload) {
            // 写入模式仅模拟对端传输成功、失败或延迟，不改写 RPC 状态。
            BasicFuture<MetChannel> result = new BasicFuture<>();
            String mode = this.owner.config().options().getOrDefault("mode", "normal");
            if (!this.active || StringUtils.equals("fail-write", mode)) {
                result.failed(new IOException("Write failed"));
                return result;
            }
            if (StringUtils.equals("throw-write", mode)) {
                throw new IllegalStateException("write threw");
            }
            if (StringUtils.equals("async-write", mode)) {
                this.writing = result;
                return result;
            }

            // 请求交付至对端订阅器；即时响应模式在写入完成前回送结果。
            if (payload instanceof RequestPayload request) {
                this.sent.add(request);
                if (StringUtils.equals("instant", mode)) {
                    ResponsePayload response = response(request, ProtocolStatus.OK);
                    this.owner.deliver(this, request.getRequestID(), response);
                } else if (!StringUtils.equals("hold", mode)) {
                    request.setReceiveTime(System.currentTimeMillis());
                    this.peer.owner.deliver(this.peer, request.getRequestID(), request);
                }
            } else {
                ResponsePayload response = (ResponsePayload) payload;
                this.replies.add(response);
                this.peer.owner.deliver(this.peer, response.getRequestID(), response);
            }
            result.completed(this);
            return result;
        }

        public Future<MetChannel> drainAndClose() {
            this.draining.countDown();
            if (this.drainGate != null) {
                return this.drainGate;
            }
            return this.close();
        }

        public Future<MetChannel> close() {
            this.active = false;
            this.owner.closed(this);
            return new BasicFuture<>(this);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
