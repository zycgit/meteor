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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.rpc.InvocationTestAccess;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 入站任务、过滤器和调度器的单元契约；受控通道不代表真实网络场景。 */
public class MetInvocationTest {
    public interface Service {
        String echo(String value);

        int length(String[] values);

        void ping();

        String fail();
    }

    @Test
    public void missingServiceIsRejectedBeforeInvocation() throws Exception {
        // 服务查找返回空值时，入站任务应在调用业务前拒绝请求。
        try (Host host = new Host()) {
            host.servicePresent = false;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void consumerServiceIsRejectedBeforeInvocation() throws Exception {
        // 消费者绑定不能被入站请求当作服务提供者。
        try (Host host = new Host()) {
            host.service.setServiceType(MetServiceType.Consumer);
            host.assertStatus(ProtocolStatus.NotFound, host.request());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void expiredClientTimeoutRejectsRequest() throws Exception {
        // 明确的客户端超时，过期请求不应进入业务方法。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setClientTimeout(500);
            request.setReceiveTime(System.currentTimeMillis() - 20000);

            // 检查拒绝状态以及未发生业务调用。
            host.assertStatus(ProtocolStatus.Timeout, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void expiredZeroClientTimeoutUsesDefaultLimit() throws Exception {
        // 零值回退到默认超时，过期请求不应进入业务方法。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setClientTimeout(0);
            request.setReceiveTime(System.currentTimeMillis() - 20000);

            // 检查拒绝状态以及未发生业务调用。
            host.assertStatus(ProtocolStatus.Timeout, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void expiredNegativeClientTimeoutUsesDefaultLimit() throws Exception {
        // 负值回退到默认超时，过期请求不应进入业务方法。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setClientTimeout(-1);
            request.setReceiveTime(System.currentTimeMillis() - 20000);

            // 检查拒绝状态以及未发生业务调用。
            host.assertStatus(ProtocolStatus.Timeout, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void expiredRequestUsesTheShorterServiceLimit() throws Exception {
        // 服务端超时短于客户端声明，过期请求不应进入业务方法。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setClientTimeout(20000);
            request.setReceiveTime(System.currentTimeMillis() - 20000);

            // 检查拒绝状态以及未发生业务调用。
            host.assertStatus(ProtocolStatus.Timeout, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void freshRequestAfterExpiryUsesDefaultTimeout() throws Exception {
        // 默认时限拒绝一个过期请求后，后续新请求仍能正常执行。
        try (Host host = new Host()) {
            RequestPayload expired = host.request();
            expired.setClientTimeout(0);
            expired.setReceiveTime(System.currentTimeMillis() - 20000);
            host.assertStatus(ProtocolStatus.Timeout, expired);
            assertEquals(0, host.invocations.get());

            // 新请求独立计算超时，不继承上一次失败状态。
            RequestPayload request = host.request();
            request.setClientTimeout(0);
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void zeroServiceAndZeroClientTimeoutReachFilters() throws Exception {
        // 服务时限 0 与客户端时限 0 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(0);
            RequestPayload request = host.request();
            request.setClientTimeout(0);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void zeroServiceAndNegativeClientTimeoutReachFilters() throws Exception {
        // 服务时限 0 与客户端时限 -1 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(0);
            RequestPayload request = host.request();
            request.setClientTimeout(-1);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void zeroServiceAndShortClientTimeoutReachFilters() throws Exception {
        // 服务时限 0 与客户端时限 2000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(0);
            RequestPayload request = host.request();
            request.setClientTimeout(2000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(2000, observed.get());
        }
    }

    @Test
    public void zeroServiceAndLongClientTimeoutReachFilters() throws Exception {
        // 服务时限 0 与客户端时限 20000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(0);
            RequestPayload request = host.request();
            request.setClientTimeout(20000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void negativeServiceAndZeroClientTimeoutReachFilters() throws Exception {
        // 服务时限 -1 与客户端时限 0 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(-1);
            RequestPayload request = host.request();
            request.setClientTimeout(0);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void negativeServiceAndNegativeClientTimeoutReachFilters() throws Exception {
        // 服务时限 -1 与客户端时限 -1 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(-1);
            RequestPayload request = host.request();
            request.setClientTimeout(-1);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void negativeServiceAndShortClientTimeoutReachFilters() throws Exception {
        // 服务时限 -1 与客户端时限 2000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(-1);
            RequestPayload request = host.request();
            request.setClientTimeout(2000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(2000, observed.get());
        }
    }

    @Test
    public void negativeServiceAndLongClientTimeoutReachFilters() throws Exception {
        // 服务时限 -1 与客户端时限 20000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(-1);
            RequestPayload request = host.request();
            request.setClientTimeout(20000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(5000, observed.get());
        }
    }

    @Test
    public void explicitServiceAndZeroClientTimeoutReachFilters() throws Exception {
        // 服务时限 1000 与客户端时限 0 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(1000);
            RequestPayload request = host.request();
            request.setClientTimeout(0);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(1000, observed.get());
        }
    }

    @Test
    public void explicitServiceAndNegativeClientTimeoutReachFilters() throws Exception {
        // 服务时限 1000 与客户端时限 -1 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(1000);
            RequestPayload request = host.request();
            request.setClientTimeout(-1);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(1000, observed.get());
        }
    }

    @Test
    public void explicitServiceAndShortClientTimeoutReachFilters() throws Exception {
        // 服务时限 1000 与客户端时限 2000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(1000);
            RequestPayload request = host.request();
            request.setClientTimeout(2000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(1000, observed.get());
        }
    }

    @Test
    public void explicitServiceAndLongClientTimeoutReachFilters() throws Exception {
        // 服务时限 1000 与客户端时限 20000 的组合在接纳和过滤器中保持一致。
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            host.service.setClientTimeout(1000);
            RequestPayload request = host.request();
            request.setClientTimeout(20000);

            // 新请求完成调用，并向过滤器暴露本组合的有效超时。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals(1000, observed.get());
        }
    }

    @Test
    public void protocolDecodedArgumentsDoNotRequireAGlobalSerializer() throws Exception {
        try (Host host = new Host()) {
            // 协议交付 Java 对象，执行器不应查询或调用全局序列化器。
            host.coders = name -> {
                throw new AssertionError("RPC must not resolve the protocol serializer");
            };
            RequestPayload request = host.request();
            request.setSerializeType("ProtocolOwned");
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals("value", response.getReturnData());
            assertEquals("ProtocolOwned", response.getSerializeType());
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void parameterCountMismatchDoesNotInvokeService() throws Exception {
        // 参数个数与签名不一致，业务方法不应执行。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.getParameterValues().clear();
            host.assertStatus(ProtocolStatus.InvokeError, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void unknownParameterTypeDoesNotInvokeService() throws Exception {
        // 参数声明引用不可加载的类型，业务方法不应执行。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.getParameterTypes().set(0, "missing.request.Type");
            host.assertStatus(ProtocolStatus.SerializeError, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void wrongParameterValueTypeDoesNotInvokeService() throws Exception {
        // 参数值与已解析的签名不匹配，业务方法不应执行。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.getParameterValues().set(0, 123);
            host.assertStatus(ProtocolStatus.InvokeError, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void missingMethodIsForbidden() throws Exception {
        // 不存在的接口方法不能进入业务实现。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setTargetMethod("missing");
            host.assertStatus(ProtocolStatus.Forbidden, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void missingProviderIsNotFound() throws Exception {
        // 已有绑定但没有提供者时返回 NotFound。
        try (Host host = new Host()) {
            host.provider = null;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
        }
    }

    @Test
    public void missingProviderInstanceIsNotFound() throws Exception {
        // 提供者存在但返回空实例时返回 NotFound。
        try (Host host = new Host()) {
            host.provider = () -> null;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
        }
    }

    @Test
    public void serviceExceptionIsUnwrappedIntoFailureResponse() throws Exception {
        // 业务异常在错误响应中保留原始原因，移除反射包装。
        try (Host host = new Host()) {
            ResponsePayload response = host.assertStatus(ProtocolStatus.InvokeError, host.request("fail"));
            assertTrue(response.getOption("message").contains("business failure"));
            assertFalse(response.getOption("message").contains("InvocationTargetException"));
        }
    }

    @Test
    public void voidResultKeepsItsDeclaredType() throws Exception {
        // 无返回值的方法仍在响应中保留 void 声明。
        try (Host host = new Host()) {
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, host.request("ping"));
            assertNull(response.getReturnData());
            assertEquals("void", response.getReturnType());
        }
    }

    @Test
    public void arrayArgumentAndPrimitiveResultKeepTheirTypes() throws Exception {
        // 以数组参数调用长度方法，验证基本类型返回值。
        try (Host host = new Host()) {
            RequestPayload request = host.request("length");
            request.addParameter(String[].class.getName(), new String[] { "one", "two" });
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertEquals(2, response.getReturnData());
            assertEquals("int", response.getReturnType());
        }
    }

    @Test
    public void nullArgumentAndResultRemainNull() throws Exception {
        // 空参数经业务方法返回后仍为空值。
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.getParameterValues().set(0, null);
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, request);
            assertNull(response.getReturnData());
        }
    }

    @Test
    public void filtersRunInOrderAndShareAttributesAndResponseOptions() throws Exception {
        // 两个过滤器嵌套执行，通过请求属性和响应选项观察同一调用链。
        try (Host host = new Host()) {
            List<String> order = new ArrayList<>();
            host.filters = new MetFilter[] { (request, response, chain) -> {
                // 第一层写入属性，后置阶段给响应追加选项。
                order.add("first-before");
                request.setAttribute("marker", "value");
                chain.doFilter(request, response);
                order.add("first-after");
                response.addOption("filtered", "yes");
            }, (request, response, chain) -> {
                // 第二层读取同一请求属性并保留嵌套执行顺序。
                order.add("second-before");
                assertEquals("value", request.getAttribute("marker"));
                chain.doFilter(request, response);
                order.add("second-after");
            } };
            host.responseOptions.addOption("server", "option");

            // 业务执行一次，前置顺序和后置逆序都保持稳定。
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, host.request());
            List<String> expectedOrder = Arrays.asList("first-before", "second-before", "second-after", "first-after");
            assertEquals(expectedOrder, order);
            assertEquals("option", response.getOption("server"));
            assertEquals("yes", response.getOption("filtered"));
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void shortCircuitingFilterSkipsInvocation() throws Exception {
        // 过滤器直接返回缓存值，不继续执行后续调用链。
        try (Host host = new Host()) {
            host.filters = new MetFilter[] { (request, response, chain) -> {
                response.sendData("cached");
            } };
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, host.request());
            assertEquals("cached", response.getReturnData());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void committedResponsePreventsLaterInvocation() throws Exception {
        // 已提交响应后继续调用链，业务方法也不应被执行。
        try (Host host = new Host()) {
            host.filters = new MetFilter[] { (request, response, chain) -> {
                response.sendData("committed");
                chain.doFilter(request, response);
            } };
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, host.request());
            assertEquals("committed", response.getReturnData());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void filterFailureBecomesInvocationError() throws Exception {
        // 过滤器抛出的异常转换为客户端可以感知的调用错误。
        try (Host host = new Host()) {
            host.filters = new MetFilter[] { (request, response, chain) -> {
                throw new IllegalStateException("filter failure");
            } };
            ResponsePayload response = host.assertStatus(ProtocolStatus.InvokeError, host.request());
            assertTrue(response.getOption("message").contains("filter failure"));
        }
    }

    @Test
    public void providerFailureBecomesInvocationError() throws Exception {
        // 服务提供者创建实例失败时保留原始错误说明。
        try (Host host = new Host()) {
            host.provider = () -> {
                throw new IllegalStateException("provider failure");
            };
            ResponsePayload response = host.assertStatus(ProtocolStatus.InvokeError, host.request());
            assertTrue(response.getOption("message").contains("provider failure"));
        }
    }

    @Test
    public void successfulMessageDoesNotSendTerminalResponse() throws Exception {
        // 单向消息执行成功后不发送最终响应。
        try (Host host = new Host()) {
            RequestPayload message = host.request();
            message.setMessage(true);
            host.run(message);
            assertTrue(host.responses.isEmpty());
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void failedMessageDoesNotSendTerminalResponse() throws Exception {
        // 单向消息的业务失败也不能发送最终响应。
        try (Host host = new Host()) {
            RequestPayload message = host.request("fail");
            message.setMessage(true);
            host.run(message);
            assertTrue(host.responses.isEmpty());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void missingMessageServiceDoesNotSendTerminalResponse() throws Exception {
        // 单向消息找不到服务时不产生最终响应。
        try (Host host = new Host()) {
            host.servicePresent = false;
            RequestPayload message = host.request();
            message.setMessage(true);
            host.run(message);
            assertTrue(host.responses.isEmpty());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void boundedQueueRejectsExcessRequestsThenContinuesAfterDrain() throws Exception {
        // 用闸门占用唯一工作线程，保留一个排队位置。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.filters = new MetFilter[] { (request, response, chain) -> {
                // 只阻塞首个请求，让后续请求填满队列。
                if (request.getRequestID() == 1) {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                }
                chain.doFilter(request, response);
            } };

            // 等待首个任务开始后，再安排排队请求与超额请求。
            try {
                host.dispatch(1);
                assertTrue(entered.await(2, TimeUnit.SECONDS));

                // 一个执行中、一个排队，第三个应立即被拒绝。
                host.dispatch(2);
                host.dispatch(3);
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());
                ResponsePayload rejected = host.take();
                assertEquals(3, rejected.getRequestID());
                assertEquals(ProtocolStatus.QueueFull, rejected.getStatus());

                // 释放工作线程，两个已接纳请求都应完成。
                release.countDown();
                assertEquals(ProtocolStatus.OK, host.take().getStatus());
                assertEquals(ProtocolStatus.OK, host.take().getStatus());
                assertEquals(2, host.invocations.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void independentQueueRejectsExcessRequests() throws Exception {
        // 独立池沿用已有容量设置，一个执行中加一个排队即达到上限。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.service.setSharedThreadPool(false);
            host.filters = new MetFilter[] { (request, response, chain) -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                chain.doFilter(request, response);
            } };

            try {
                host.dispatch(1);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());
                host.dispatch(2);
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());

                // 拒绝必须立即通过现有协议状态返回给调用方。
                host.dispatch(3);
                ResponsePayload rejected = host.take();
                assertEquals(3, rejected.getRequestID());
                assertEquals(ProtocolStatus.QueueFull, rejected.getStatus());
            } finally {
                release.countDown();
            }
            assertEquals(ProtocolStatus.OK, host.take().getStatus());
            assertEquals(ProtocolStatus.OK, host.take().getStatus());
            assertEquals(2, host.invocations.get());
        }
    }

    @Test
    public void removingServiceRejectsQueuedIndependentCallsAndInterruptsActiveWork() throws Exception {
        // 服务尚有运行任务和排队任务时，模拟容器发出的真实服务移除事件。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.service.setSharedThreadPool(false);
            host.filters = new MetFilter[] { (request, response, chain) -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    fail("Service removal must interrupt its worker");
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
            } };

            // 接纳一个运行请求和一个排队请求，再触发服务移除。
            host.dispatch(1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(ProtocolStatus.Accept, host.take().getStatus());
            host.dispatch(2);
            assertEquals(ProtocolStatus.Accept, host.take().getStatus());

            // 移除后不能静默丢弃已接受的排队请求，也不能再进入业务方法。
            host.servicePresent = false;
            host.listener.onEvent(new MetContextEvent(MetContextEventType.REMOVED, host.service));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            ResponsePayload first = host.take();
            ResponsePayload second = host.take();
            ResponsePayload queued = second;
            if (first.getRequestID() == 2) {
                queued = first;
            }
            assertEquals(2, queued.getRequestID());
            assertEquals(ProtocolStatus.NotFound, queued.getStatus());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void dispatcherCloseInterruptsActiveWorkAndDiscardsQueuedWork() throws Exception {
        // 准备一个执行中任务和一个排队任务，等待关闭发出中断。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.filters = new MetFilter[] { (request, response, chain) -> {
                entered.countDown();
                try {
                    // 等待关闭中断；即使关闭逻辑失效，也不能永久占用工作线程。
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    fail("Dispatcher close must interrupt the running task");
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            } };
            host.dispatch(1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            host.dispatch(2);

            // 关闭打断执行中的任务，排队任务不得继续调用服务。
            host.dispatcher.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, host.invocations.get());
            host.dispatch(3);
            // 检查两个接纳回执、中断响应和关闭后的拒绝，不允许排队任务产生业务响应。
            boolean rejected = false;
            for (int i = 0; i < 4; i++) {
                ResponsePayload response = host.take();
                if (response.getRequestID() == 3) {
                    assertEquals(ProtocolStatus.QueueFull, response.getStatus());
                    rejected = true;
                }
                assertFalse(response.getRequestID() == 2 && response.getStatus() != ProtocolStatus.Accept);
            }
            assertTrue(rejected);
        }
    }

    private static final class Host implements AutoCloseable {
        private final ServiceDomain<Service>           service         = new ServiceDomain<>(Service.class);
        private final AtomicInteger                    invocations     = new AtomicInteger();
        private final BlockingQueue<ResponsePayload>   responses       = new LinkedBlockingQueue<>();
        private final OptionInfo                       responseOptions = new OptionInfo();
        private final InvocationTestAccess             dispatcher;
        private final MetChannel                       channel;
        private       boolean                          servicePresent  = true;
        private       Function<String, SerializeCoder> coders          = name -> new JavaSerializeCoder();
        private       MetFilter[]                      filters         = new MetFilter[0];
        private       MetContextListener               listener;
        private       Supplier<Service>                provider        = () -> new Service() {
            public String echo(String value) {
                Host.this.invocations.incrementAndGet();
                return value;
            }

            public int length(String[] values) {
                Host.this.invocations.incrementAndGet();
                return values.length;
            }

            public void ping() {
                Host.this.invocations.incrementAndGet();
            }

            public String fail() {
                throw new IllegalArgumentException("business failure");
            }
        };

        private Host() {
            this.service.setBindName("Service");
            this.service.setServiceType(MetServiceType.Provider);
            this.service.setClientTimeout(10000);
            this.service.setSerializeType("Java");
            // 只提供入站执行器所需的宿主配置与生命周期契约。
            MetSettings settings = proxy(MetSettings.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                    case "getQueueMaxSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getDefaultTimeout":
                        return 5000;
                    case "getResponseOptions":
                        return this.responseOptions;
                    default:
                        throw new AssertionError(method);
                }
            });
            MetContext context = proxy(MetContext.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getSettings":
                        return settings;
                    case "subscribe":
                        this.listener = (MetContextListener) args[0];
                        return (Cancellable) () -> {
                            this.listener = null;
                            return true;
                        };
                    case "getSerializeCoder":
                        return this.coders.apply((String) args[0]);
                    case "getClassLoader":
                        return this.getClass().getClassLoader();
                    case "getServiceInfo":
                        return this.servicePresent ? this.service : null;
                    case "getServiceProvider":
                        return this.provider;
                    default:
                        throw new AssertionError(method);
                }
            });
            // 通道收集生产任务发出的响应，测试不复刻执行或调度逻辑。
            this.channel = proxy(MetChannel.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getRemote":
                        return new InterAddress("rsf://localhost:2181/default");
                    case "sendData":
                        this.responses.add((ResponsePayload) args[0]);
                        return new BasicFuture<>((MetChannel) p);
                    default:
                        throw new AssertionError(method);
                }
            });
            this.dispatcher = new InvocationTestAccess(context, id -> this.suppliers());
        }

        @SuppressWarnings("unchecked")
        private Supplier<MetFilter>[] suppliers() {
            Supplier<MetFilter>[] suppliers = new Supplier[this.filters.length];
            for (int i = 0; i < this.filters.length; i++) {
                MetFilter filter = this.filters[i];
                suppliers[i] = () -> filter;
            }
            return suppliers;
        }

        private RequestPayload request() {
            RequestPayload request = this.request("echo");
            request.addParameter(String.class.getName(), "value");
            return request;
        }

        private RequestPayload request(String method) {
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            request.setServiceGroup(this.service.getBindGroup());
            request.setServiceName(this.service.getBindName());
            request.setServiceVersion(this.service.getBindVersion());
            request.setSerializeType("Java");
            request.setTargetMethod(method);
            request.setClientTimeout(10000);
            request.setReceiveTime(System.currentTimeMillis());
            return request;
        }

        private void run(RequestPayload request) {
            this.dispatcher.run(this.channel, request);
        }

        private ResponsePayload assertStatus(short status, RequestPayload request) throws Exception {
            this.run(request);
            ResponsePayload response = this.take();
            assertEquals(status, response.getStatus());
            assertEquals(request.getRequestID(), response.getRequestID());
            return response;
        }

        private ResponsePayload take() throws Exception {
            ResponsePayload response = this.responses.poll(3, TimeUnit.SECONDS);
            assertNotNull("Expected response", response);
            return response;
        }

        private void dispatch(long id) {
            RequestPayload request = this.request();
            request.setRequestID(id);
            this.dispatcher.onRequest(this.channel, id, request);
        }

        public void close() {
            this.dispatcher.close();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
