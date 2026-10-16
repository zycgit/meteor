/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetCallerLifecycleTest {
    public interface Echo {
        String echo(String value);

        @MetMessage
        MetResult notify(String value);

        String notify(int value);
    }

    public interface InheritedEcho extends Echo {
    }

    @MetMessage
    public interface MessageService {
        void signal(String value);
    }

    @Test
    public void requestOptionFailureAbortsCreationBeforeFiltersOrSending() throws Exception {
        // 设置读取默认请求选项时抛出的原始异常，并记录过滤器是否执行。
        try (Host host = new Host(1)) {
            IllegalStateException cause = new IllegalStateException("request options unavailable");
            host.requestOptionFailure = cause;
            AtomicInteger filters = new AtomicInteger();
            host.filter = (request, response, chain) -> {
                filters.incrementAndGet();
                chain.doFilter(request, response);
            };

            // 创建请求时复制选项失败，尚未进入过滤器和发送阶段。
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                host.caller.createRequest(target, host.service, "echo", types, arguments);
            });
            assertSame(cause, failure);
            assertEquals(0, filters.get());
            assertTrue(host.sent.isEmpty());
            assertTrue(host.timers.isEmpty());

            // 清除故障后，未完成的请求创建不应占用发送名额。
            host.requestOptionFailure = null;
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void methodAnnotationResolvesMessageBeforeFilters() throws Exception {
        // 同一接口只有 notify 方法声明消息模式，服务本身保持普通调用配置。
        try (Host host = new Host(1)) {
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // createRequest 在过滤器执行前解析逐方法调用模式。
            MetRequestObject request = host.caller.createRequest(target, host.service, "notify", types, arguments);
            assertTrue(request.isMessage());
            assertFalse(host.service.isMessage());
        }
    }

    @Test
    public void ordinaryMethodInMixedServiceRemainsRequestResponse() throws Exception {
        // notify 上的消息注解不能改变同一接口中 echo 方法的调用模式。
        try (Host host = new Host(1)) {
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // 普通方法仍创建等待业务结果的请求。
            MetRequestObject request = host.caller.createRequest(target, host.service, "echo", types, arguments);
            assertFalse(request.isMessage());
        }
    }

    @Test
    public void methodAnnotationAlsoAppliesToSoapTargets() throws Exception {
        // 方法的消息声明对 SOAP 地址同样生效，无需绑定资源提供模式解析。
        try (Host host = new Host(1)) {
            InterAddress address = new InterAddress("soap://127.0.0.1:2181/services/EchoService");
            InstanceAddressProvider target = new InstanceAddressProvider(address);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // 目标协议只决定通信方式，不改变注解声明的调用模式。
            MetRequestObject request = host.caller.createRequest(target, host.service, "notify", types, arguments);
            assertTrue(request.isMessage());
        }
    }

    @Test
    public void unannotatedOverloadRemainsRequestResponse() throws Exception {
        // 同名重载按参数签名区分，只有 String 参数的方法声明了消息模式。
        try (Host host = new Host(1)) {
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { int.class };
            Object[] arguments = { 1 };

            // int 参数的重载继续等待业务响应。
            MetRequestObject request = host.caller.createRequest(target, host.service, "notify", types, arguments);
            assertFalse(request.isMessage());
        }
    }

    @Test
    public void typeAnnotationStillMarksUnannotatedMethodsAsMessages() throws Exception {
        // 接口级注解保持原有语义，方法本身无需重复声明。
        try (Host host = new Host(1)) {
            ServiceDomain<MessageService> service = new ServiceDomain<>(MessageService.class);
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // 使用同一创建入口，确认类型上的声明被带入请求。
            MetRequestObject request = host.caller.createRequest(target, service, "signal", types, arguments);
            assertTrue(request.isMessage());
        }
    }

    @Test
    public void serviceMessageSettingStillAppliesToUnannotatedMethods() throws Exception {
        // asMessage 对应的服务级设置继续作用于所有方法。
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // 未标注消息注解的 echo 也继承服务级消息模式。
            MetRequestObject request = host.caller.createRequest(target, host.service, "echo", types, arguments);
            assertTrue(request.isMessage());
        }
    }

    @Test
    public void inheritedMethodKeepsItsMessageAnnotation() throws Exception {
        // 子接口直接继承父接口声明的消息方法。
        try (Host host = new Host(1)) {
            ServiceDomain<InheritedEcho> service = new ServiceDomain<>(InheritedEcho.class);
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };

            // 方法查找保留原声明上的注解，子接口不需要重复标注。
            MetRequestObject request = host.caller.createRequest(target, service, "notify", types, arguments);
            assertTrue(request.isMessage());
        }
    }

    @Test
    public void messageFilterCanCompleteLocallyWithOrdinarySuccess() throws Exception {
        // 过滤器提前完成一个由方法注解声明的消息调用。
        try (Host host = new Host(1)) {
            host.filter = (request, response, chain) -> response.sendData(null);
            InstanceAddressProvider target = new InstanceAddressProvider(host.peer);
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };
            MetRequestObject request = host.caller.createRequest(target, host.service, "notify", types, arguments);

            // 本地处理已经完成，OK 直接完成 Future，无需等待远端的入队回执。
            MetFuture call = host.caller.invoke(request);
            MetResponse response = call.get(2, TimeUnit.SECONDS);
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertNull(response.getData());
            assertTrue(host.sent.isEmpty());
        }
    }

    @Test
    public void messageFilterCanCompleteWithAcceptance() throws Exception {
        // 消息处理过滤器直接确认受理，继续支持已有的 Accept 完成方式。
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            host.filter = (request, response, chain) -> response.sendStatus(ProtocolStatus.Accept);

            // 确认受理后完成 Future，不继续发送网络请求。
            MetFuture call = host.invoke();
            assertEquals(ProtocolStatus.Accept, call.get(2, TimeUnit.SECONDS).getStatus());
            assertTrue(host.sent.isEmpty());
        }
    }

    @Test
    public void rejectedMessagePreservesStatusAndDescriptionAndReleasesCapacity() throws Exception {
        // 将服务配置为消息调用，等待远端拒绝接收。
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            MetFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.Forbidden);
            response.addOption("message", "message was not accepted");

            // 拒绝状态与描述传给调用方，并归还唯一名额。
            host.manager.deliver(host.channel, response.getRequestID(), response);
            MetException failure = (MetException) assertFailure(call, MetException.class);
            assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
            assertTrue(failure.getMessage().contains("message was not accepted"));
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void ordinarySuccessStatusIsFailureForMessageAndReleasesCapacity() throws Exception {
        // 消息调用要求 Accept，普通调用的 OK 不能作为消息回执。
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            MetFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.addOption("message", "message was not accepted");

            // 保留不匹配的状态和远端描述，并归还唯一名额。
            host.manager.deliver(host.channel, response.getRequestID(), response);
            MetException failure = (MetException) assertFailure(call, MetException.class);
            assertEquals(ProtocolStatus.OK, failure.getStatus());
            assertTrue(failure.getMessage().contains("message was not accepted"));
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void successfulResponsePreservesOptionsAndNullResult() throws Exception {
        // 构造携带 trace 选项且未设置返回数据的成功响应。
        try (Host host = new Host(1)) {
            MetFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.addOption("trace", "server-trace");

            // 同一次成功响应保留空返回值、成功状态与附带选项。
            host.manager.deliver(host.channel, response.getRequestID(), response);
            MetResponse result = call.get(2, TimeUnit.SECONDS);
            assertNull(result.getData());
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertEquals("server-trace", result.getOption("trace"));
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void duplicateSubmissionDoesNotRemoveOriginalPendingRequest() throws Exception {
        // 在容量为二的调用器中提交一个请求。
        try (Host host = new Host(2)) {
            MetRequestObject request = host.request(host.peer, new Object[] { "value" });
            MetFuture original = host.send(request);

            // 重复提交不能摘除仍在等待响应的原始请求。
            MetFuture duplicate = host.send(request);
            assertTrue(duplicate.getCause() instanceof IllegalStateException);
            assertFalse(original.isDone());
            assertEquals(1, host.sent.size());

            // 重复提交只归还自身预留名额，另一个请求仍可占用剩余名额。
            MetFuture other = host.invoke();
            assertFalse(other.isDone());
            assertRejected(host.invoke());

            // 原请求仍可由正确响应完成。
            host.respond(original, ProtocolStatus.OK);
            assertEquals("done", original.getData());
            other.cancel();
        }
    }

    @Test
    public void repeatedCancellationCannotRemoveAnotherFutureUsingSameRequest() throws Exception {
        // 取消原调用后，准备复用同一个请求对象。
        try (Host host = new Host(1)) {
            MetRequestObject request = host.request(host.peer, new Object[] { "value" });
            MetFuture cancelled = host.send(request);
            cancelled.cancel();

            // 相同请求对象已归属于新 Future，旧 Future 的重复取消不能影响它。
            MetFuture current = host.send(request);
            cancelled.cancel();
            assertFalse(current.isDone());
            assertRejected(host.invoke());

            // 新 Future 保持可响应，完成后只释放自身名额。
            host.respond(current, ProtocolStatus.OK);
            assertEquals("done", current.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void messageAcceptReturnsReceiptForRsfResultMethod() throws Exception {
        // 选择返回 MetResult 的消息方法，并发送消息请求。
        try (Host host = new Host(1)) {
            MetContext context = host.caller.getContext();
            Method method = Echo.class.getMethod("notify", String.class);
            Object[] arguments = { "value" };
            MetRequestObject request = new MetRequestObject(1000000L, true, context, host.service, method, arguments);
            host.configureRequest(request, host.peer);
            request.setMessage(true);
            MetFuture future = host.send(request);

            // Accept 是消息调用的最终回执，返回成功标识和对应的请求 ID。
            host.respond(future, ProtocolStatus.Accept);
            MetResult result = (MetResult) future.getData();
            assertTrue(result.isSuccess());
            assertEquals(request.getRequestID(), result.getMessageID());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void mismatchedEnvelopeIdLeavesOriginalRequestPending() throws Exception {
        // 为一个挂起请求构造 ID 正确的响应体。
        try (Host host = new Host(1)) {
            MetFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            long requestId = call.getRequest().getRequestID();
            response.setRequestID(requestId);
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("reply");

            // 信封 ID 不匹配时忽略响应，正确 ID 仍可完成原请求。
            host.manager.deliver(host.channel, requestId + 1, response);
            assertFalse(call.isDone());
            host.manager.deliver(host.channel, requestId, response);
            assertEquals("reply", call.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void nullFilterListAllowsSuccessfulResponse() throws Exception {
        // 过滤器提供方允许返回 null，普通调用仍可发送。
        try (Host host = new Host(1)) {
            host.noFilters = true;
            MetFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("reply");

            // 没有过滤器时仍交付响应结果，并释放名额。
            host.manager.deliver(host.channel, response.getRequestID(), response);
            assertEquals("reply", call.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void nullFilterListAllowsTransportFailure() throws Exception {
        // 过滤器提供方返回 null，创建一个等待传输结果的调用。
        try (Host host = new Host(1)) {
            host.noFilters = true;
            MetFuture failed = host.invoke();
            IllegalStateException cause = new IllegalStateException("transport failed");
            long requestId = failed.getRequest().getRequestID();

            // 传输异常原样传给调用方，失败后唯一名额可再次使用。
            host.manager.deliver(host.channel, requestId, new ThrowPayload(cause));
            assertSame(cause, assertFailure(failed, IllegalStateException.class));
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void closeContinuesFailingRequestsWhenCompletionListenerThrows() throws Exception {
        // 准备两个挂起请求，让第一个请求的失败监听器抛出异常。
        try (Host host = new Host(2)) {
            MetFuture first = host.invoke();
            AtomicInteger failures = new AtomicInteger();
            first.onFailed(done -> {
                failures.incrementAndGet();
                throw new IllegalArgumentException("broken callback");
            });
            MetFuture second = host.invoke();

            // 关闭时首个失败监听器抛异常，仍应继续终止其他挂起请求。
            host.caller.close();
            assertEquals(1, failures.get());
            assertTrue(first.isDone());
            assertFailure(second, IllegalStateException.class);
        }
    }

    @Test
    public void reservesSlotBeforeMappingAndRegistration() throws Exception {
        // 在单独线程阻塞请求映射，观察注册前的容量占用。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            MetRequestObject request = host.blockedRequest(mapping, resume);
            Future<MetFuture> first = executor.submit(() -> {
                return host.send(request);
            });

            // 映射尚未完成时已预留名额，不能再接纳其他请求。
            assertTrue(mapping.await(2, TimeUnit.SECONDS));
            assertTrue(host.sent.isEmpty());
            assertRejected(host.invoke());

            // 恢复映射后完成发送，取消调用会归还预留名额。
            resume.countDown();
            MetFuture admitted = first.get(2, TimeUnit.SECONDS);
            assertFalse(admitted.isDone());
            assertEquals(1, host.sent.size());
            admitted.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentAdmissionNeverExceedsMaximum() throws Exception {
        // 准备二十四个同时竞争三个名额的调用。
        ExecutorService executor = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        try (Host host = new Host(3)) {
            List<Future<MetFuture>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                calls.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    return host.invoke();
                }));
            }

            // 同时放行全部请求，完成的请求必须都属于限流拒绝。
            start.countDown();
            int admitted = 0;
            for (Future<MetFuture> call : calls) {
                MetFuture future = call.get(3, TimeUnit.SECONDS);
                if (future.isDone()) {
                    assertRejected(future);
                } else {
                    admitted++;
                }
            }

            // 只有最大容量允许的三个请求被接纳并发送。
            assertEquals(3, admitted);
            assertEquals(3, host.sent.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void sendCompletionKeepsSlotUntilFinalResponse() throws Exception {
        // 普通调用完成发送后，仍需要等待远端最终响应。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            AtomicInteger completions = new AtomicInteger();
            future.onCompleted(done -> {
                completions.incrementAndGet();
            });

            // 发送成功不能完成 RPC 或提前释放名额。
            host.callbacks.remove().completed(host.channel);
            assertFalse(future.isDone());
            assertEquals(0, completions.get());
            assertRejected(host.invoke());

            // 最终响应才完成调用，并归还唯一名额。
            host.respond(future, ProtocolStatus.OK);
            assertEquals("done", future.getData());
            assertEquals(1, completions.get());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void ordinaryAcceptKeepsSlotUntilFinalResponse() throws Exception {
        // 为普通调用准备一个中间 Accept 确认。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();

            // 中间确认不完成普通调用，也不释放请求名额。
            host.respond(future, ProtocolStatus.Accept);
            assertFalse(future.isDone());
            assertRejected(host.invoke());

            // 最终响应交付结果并归还名额。
            host.respond(future, ProtocolStatus.OK);
            assertEquals("done", future.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void duplicateFinalResponseCompletesOnceAndReleasesOneSlot() throws Exception {
        // 记录普通调用的成功完成次数。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            AtomicInteger completions = new AtomicInteger();
            future.onCompleted(done -> {
                completions.incrementAndGet();
            });
            host.respond(future, ProtocolStatus.OK);
            assertEquals("done", future.getData());

            // 重复响应不能再次完成调用或多归还名额。
            host.respond(future, ProtocolStatus.OK);
            assertEquals(1, completions.get());
            assertEquals("done", future.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void messageAcceptCompletesCallAndReleasesSlot() throws Exception {
        // 将普通返回类型的接口配置为消息调用。
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            MetFuture message = host.invoke();

            // Accept 是消息的最终回执，接收后立即完成并释放名额。
            host.respond(message, ProtocolStatus.Accept);
            assertTrue(message.isDone());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void remoteErrorFailsOrdinaryCallAndReleasesSlot() throws Exception {
        // 普通调用占用唯一名额并等待远端响应。
        try (Host host = new Host(1)) {
            MetFuture rejected = host.invoke();

            // 远端错误终止调用，释放的名额允许后续请求进入。
            host.respond(rejected, ProtocolStatus.Forbidden);
            assertFailure(rejected, MetException.class);
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void repeatedTimeoutReleasesExactlyOneSlot() throws Exception {
        // 为挂起请求取得手动控制的超时任务。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            Runnable timer = host.timers.remove();

            // 同一个超时任务重复触发，只终止一次调用并归还一个名额。
            timer.run();
            timer.run();
            assertFailure(future, MetTimeoutException.class);
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void lateResponsePreservesTimeoutAndDoesNotReleaseAnotherSlot() throws Exception {
        // 先触发超时并保存调用方收到的原始异常。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            host.timers.remove().run();
            Throwable timeout = assertFailure(future, MetTimeoutException.class);

            // 迟到的成功响应不能覆盖超时结果，也不能再次释放名额。
            host.respond(future, ProtocolStatus.OK);
            assertSame(timeout, assertFailure(future, MetTimeoutException.class));
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void cancellationNotifiesOnceAndReleasesSlot() throws Exception {
        // 记录一个挂起请求的取消通知与终态通知次数。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            AtomicInteger cancellations = new AtomicInteger();
            AtomicInteger terminal = new AtomicInteger();
            future.onCancel(done -> {
                cancellations.incrementAndGet();
            });
            future.onFinal(done -> {
                terminal.incrementAndGet();
            });

            // 重复取消只触发一次取消通知与终态通知，并归还一个名额。
            assertTrue(future.cancel());
            assertFalse(future.cancel());
            assertEquals(1, cancellations.get());
            assertEquals(1, terminal.get());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void throwingCancellationListenerStillReleasesSlot() throws Exception {
        // 让取消监听器抛异常，并记录后续终态通知是否执行。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            AtomicBoolean terminal = new AtomicBoolean();
            future.onCancel(done -> {
                throw new IllegalArgumentException("callback");
            });
            future.onFinal(done -> {
                terminal.set(true);
            });

            // 监听器抛异常不阻止取消、终态通知和名额回收。
            assertTrue(future.cancel());
            assertTrue(future.isCancelled());
            assertTrue(terminal.get());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void throwingCompletionListenerDoesNotLeakSlot() throws Exception {
        // 让成功监听器抛异常，并记录后续终态通知。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            AtomicBoolean terminal = new AtomicBoolean();
            future.onCompleted(done -> {
                throw new IllegalArgumentException("callback");
            });
            future.onFinal(done -> {
                terminal.set(true);
            });

            // 成功监听器抛异常时，结果、终态通知和名额回收仍正常。
            host.respond(future, ProtocolStatus.OK);
            assertTrue(terminal.get());
            assertEquals("done", future.getData());
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void immediateMappingFailureNotifiesLateListenerAndAllowsRetry() throws Exception {
        // 以缺少实参的请求触发同步映射失败。
        try (Host host = new Host(1)) {
            BlockingQueue<MetFuture> retried = new LinkedBlockingQueue<>();
            MetRequestObject request = host.request(host.peer, new Object[0]);
            MetFuture failed = host.send(request);
            assertFailure(failed, ArrayIndexOutOfBoundsException.class);

            // 事后注册的监听器立即收到失败，并可重新进入调用器发送新请求。
            failed.onFailed(done -> {
                retried.add(host.invoke());
            });
            MetFuture retry = retried.remove();
            assertFalse(retry.isDone());
            assertRejected(host.invoke());

            // 重入调用独占唯一名额，取消后该名额重新可用。
            retry.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void cancellingTimeoutDoesNotBlockAnotherInvocation() throws Exception {
        // 通过可阻塞的定时器取消动作观察调用器是否持有全局锁。
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            host.timerCancellation = () -> {
                // 用闩锁停在定时器取消内部，暴露取消与后续请求的交错。
                cancelling.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return true;
            };
            MetFuture first = host.invoke();
            host.timerCancellation = () -> true;

            // 完成首个响应，使其停在定时器取消期间。
            Future<?> responding = executor.submit(() -> {
                host.respond(first, ProtocolStatus.OK);
            });
            MetFuture next;
            try {
                assertTrue(cancelling.await(2, TimeUnit.SECONDS));

                // 定时器尚未取消完成时，另一个调用仍能取得刚释放的名额。
                Future<MetFuture> invoking = executor.submit(host::invoke);
                next = invoking.get(2, TimeUnit.SECONDS);
                assertFalse(next.isDone());
                assertRejected(host.invoke());
            } finally {
                release.countDown();
            }

            // 放行取消动作后，两次调用都能正常结束且容量保持为一。
            responding.get(2, TimeUnit.SECONDS);
            assertEquals("done", first.getData());
            next.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void competingTerminalEventsDoNotInflateCapacity() throws Exception {
        // 使用四个线程并发触发取消、超时、成功响应与传输失败。
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try (Host host = new Host(1)) {
            for (int round = 0; round < 20; round++) {
                // 重复同一竞争场景，令四种终态同时争夺当前请求。
                host.timers.clear();
                MetFuture future = host.invoke();
                Runnable timer = host.timers.remove();
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Void>> actions = new ArrayList<>();
                actions.add(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    future.cancel();
                    return null;
                });
                actions.add(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    timer.run();
                    return null;
                });
                actions.add(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    host.respond(future, ProtocolStatus.OK);
                    return null;
                });
                actions.add(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    long requestId = future.getRequest().getRequestID();
                    ThrowPayload failure = new ThrowPayload(new IllegalStateException("send failed"));
                    host.manager.deliver(host.channel, requestId, failure);
                    return null;
                });

                // 所有竞争动作完成后，请求结束且只能归还一个名额。
                List<Future<Void>> results = new ArrayList<>();
                for (Callable<Void> action : actions) {
                    results.add(executor.submit(action));
                }
                start.countDown();
                for (Future<Void> result : results) {
                    result.get(2, TimeUnit.SECONDS);
                }
                assertTrue(future.isDone());
                MetFuture available = host.invoke();
                assertFalse("Slot must be reusable", available.isDone());
                assertRejected(host.invoke());
                assertTrue(available.cancel());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void missingAddressFailureDoesNotSendOrLeakSlot() throws Exception {
        // 缺少目标地址的请求不能进入传输层。
        try (Host host = new Host(1)) {
            MetRequestObject request = host.request(null, new Object[] { "value" });
            MetFuture failed = host.send(request);
            assertFailure(failed, MetException.class);
            assertTrue(host.sent.isEmpty());

            // 地址错误不占用后续请求的名额。
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void mappingFailureDoesNotSendOrLeakSlot() throws Exception {
        // 参数个数不匹配，使请求在报文映射阶段失败。
        try (Host host = new Host(1)) {
            MetRequestObject request = host.request(host.peer, new Object[0]);
            MetFuture failed = host.send(request);
            assertFailure(failed, ArrayIndexOutOfBoundsException.class);
            assertTrue(host.sent.isEmpty());

            // 映射失败归还已预留的名额。
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void timerRejectionDoesNotSendOrLeakSlot() throws Exception {
        // 调度器拒绝注册请求的超时任务。
        try (Host host = new Host(1)) {
            host.rejectTimer = true;
            assertFailure(host.invoke(), RejectedExecutionException.class);
            assertTrue(host.sent.isEmpty());

            // 调度器恢复后，失败调用的名额可再次使用。
            host.rejectTimer = false;
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void immediateTimeoutDoesNotSendOrLeakSlot() throws Exception {
        // 在注册超时任务时立即触发超时。
        try (Host host = new Host(1)) {
            host.immediateTimeout = true;
            assertFailure(host.invoke(), MetTimeoutException.class);
            assertTrue(host.sent.isEmpty());

            // 超时发生在发送前，且只归还一个名额。
            host.immediateTimeout = false;
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void synchronousSendFailureReleasesSlot() throws Exception {
        // 传输层在 sendData 调用时直接抛出异常。
        try (Host host = new Host(1)) {
            host.failSend = true;
            assertFailure(host.invoke(), IllegalStateException.class);

            // 同步发送失败不占用恢复后的请求名额。
            host.failSend = false;
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void asynchronousSendFailureReleasesSlotOnlyOnce() throws Exception {
        // 发送操作先返回 Future，随后异步失败。
        try (Host host = new Host(1)) {
            MetFuture future = host.invoke();
            BasicFuture<MetChannel> callback = host.callbacks.remove();

            // 重复失败通知不能多次归还同一个请求的名额。
            callback.failed(new IllegalArgumentException("async failure"));
            callback.failed(new IllegalArgumentException("duplicate"));
            assertFailure(future, IllegalArgumentException.class);
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void sendFutureCancellationFailsRpcAndReleasesItsSlot() throws Exception {
        // 创建一个正在等待传输层发送完成的调用。
        try (Host host = new Host(1)) {
            MetFuture request = host.invoke();

            // 发送 Future 被取消后，RPC 以取消异常结束并归还名额。
            BasicFuture<MetChannel> sending = host.callbacks.remove();
            assertTrue(sending.cancel());
            assertFailure(request, CancellationException.class);
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void filterFailureAfterSendingReleasesSlot() throws Exception {
        // 过滤器先继续发送，再抛出异常。
        try (Host host = new Host(1)) {
            host.filter = (request, response, chain) -> {
                chain.doFilter(request, response);
                throw new IllegalStateException("filter failed after send");
            };
            assertFailure(host.invoke(), IllegalStateException.class);

            // 清除失败过滤器后，已发送请求占用的名额应已归还。
            host.filter = null;
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void localFilterResponseNeedsNoSlot() throws Exception {
        // 用普通调用占满唯一名额，再配置本地响应过滤器。
        try (Host host = new Host(1)) {
            MetFuture pending = host.invoke();
            host.filter = (request, response, chain) -> {
                response.sendData("local");
                chain.doFilter(request, response);
            };

            // 本地响应不受发送名额限制，也不释放其他调用的名额。
            MetFuture local = host.invoke();
            assertEquals("local", local.getData());
            host.filter = null;
            assertRejected(host.invoke());

            // 挂起调用取消后，才归还其拥有的名额。
            pending.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void waitingSenderContinuesAsSoonAsSlotIsReleased() throws Exception {
        // 先占满唯一名额，再通过 WaitSecond 策略发送等待请求。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Host host = new Host(1)) {
            MetFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            Future<MetFuture> waiting = executor.submit(host::invoke);

            // 等待调用确实触发限流后，取消占用名额的首个请求。
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));
            first.cancel();

            // 等待者无需耗尽一秒等待期就能取得名额并发送。
            MetFuture admitted = waiting.get(700, TimeUnit.MILLISECONDS);
            assertFalse(admitted.isDone());
            host.policy = SendLimitPolicy.Reject;
            assertRejected(host.invoke());
            admitted.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void waitingSenderTimesOutWithoutTakingSlot() throws Exception {
        // 让已有请求占满容量，并配置等待名额的发送策略。
        try (Host host = new Host(1)) {
            MetFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;

            // 无人归还名额时，等待一秒后以限流异常结束且没有发送。
            long start = System.nanoTime();
            assertRejected(host.invoke());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis >= 900);
            assertEquals(1, host.sent.size());

            // 取消原请求后，超时的等待者没有占用或额外归还名额。
            host.policy = SendLimitPolicy.Reject;
            first.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        }
    }

    @Test
    public void interruptedWaitPreservesInterruptAndDoesNotLeak() throws Exception {
        // 占满容量后，在可观察的线程中等待发送名额。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (Host host = new Host(1)) {
            MetFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            BlockingQueue<Thread> worker = new LinkedBlockingQueue<>();
            Future<MetFuture> waiting = executor.submit(() -> {
                // 暴露等待线程，并在调用返回后读取其真实中断标志。
                worker.add(Thread.currentThread());
                MetFuture future = host.invoke();
                interrupted.set(Thread.currentThread().isInterrupted());
                return future;
            });

            // 等待者进入限流路径后，中断应传给调用方且保留线程标志。
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));
            worker.remove().interrupt();
            MetFuture interruptedCall = waiting.get(2, TimeUnit.SECONDS);
            assertFailure(interruptedCall, InterruptedException.class);
            assertTrue(interrupted.get());
            assertEquals(1, host.sent.size());

            // 中断没有发送新请求，也没有泄漏名额。
            host.policy = SendLimitPolicy.Reject;
            first.cancel();
            MetFuture available = host.invoke();
            assertFalse("Slot must be reusable", available.isDone());
            assertRejected(host.invoke());
            assertTrue(available.cancel());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shutdownFailsPendingRequest() throws Exception {
        // 创建已经发送、尚未收到响应的请求。
        try (Host host = new Host(1)) {
            MetFuture pending = host.invoke();

            // 关闭会终止挂起请求，并将关闭异常交付给调用方。
            host.close();
            assertFailure(pending, IllegalStateException.class);
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void shutdownFailsWaitingRequestWithoutSendingAgain() throws Exception {
        // 占满容量，让另一个请求进入 WaitSecond 等待。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Host host = new Host(1)) {
            MetFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            Future<MetFuture> waiting = executor.submit(host::invoke);
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));

            // 关闭释放名额后，等待者仍须失败，不能趁机再次发送。
            host.close();
            MetFuture failed = waiting.get(2, TimeUnit.SECONDS);
            assertFailure(failed, IllegalStateException.class);
            assertFailure(first, IllegalStateException.class);
            assertEquals(1, host.sent.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void repeatedShutdownIsIdempotent() throws Exception {
        // 首次关闭终止一个挂起请求。
        try (Host host = new Host(1)) {
            MetFuture pending = host.invoke();
            host.close();
            Throwable failure = assertFailure(pending, IllegalStateException.class);

            // 再次关闭不抛异常，也不改变已经交付的关闭结果。
            host.close();
            assertSame(failure, assertFailure(pending, IllegalStateException.class));
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void invocationAfterShutdownFailsWithoutSending() throws Exception {
        // 关闭已处理过一个挂起请求的调用器。
        try (Host host = new Host(1)) {
            MetFuture first = host.invoke();
            host.close();
            assertFailure(first, IllegalStateException.class);

            // 关闭后的新调用立即失败，不能增加已发送请求数。
            MetFuture rejected = host.invoke();
            assertFailure(rejected, IllegalStateException.class);
            assertEquals(1, host.sent.size());
        }
    }

    @Test
    public void shutdownDuringMappingPreventsRegistrationAndSend() throws Exception {
        // 让请求停在报文映射阶段，准备与关闭动作交错。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            MetRequestObject request = host.blockedRequest(mapping, resume);
            Future<MetFuture> call = executor.submit(() -> {
                return host.send(request);
            });
            assertTrue(mapping.await(2, TimeUnit.SECONDS));

            // 映射期间关闭调用器，再放行映射，禁止继续注册与发送。
            host.close();
            resume.countDown();
            MetFuture failed = call.get(2, TimeUnit.SECONDS);
            assertFailure(failed, IllegalStateException.class);
            assertTrue(host.sent.isEmpty());
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertRejected(MetFuture future) throws Exception {
        MetException failure = (MetException) assertFailure(future, MetException.class);
        assertEquals(ProtocolStatus.SendLimitPolicy, failure.getStatus());
    }

    private static Throwable assertFailure(MetFuture future, Class<? extends Throwable> type) throws Exception {
        // 有界等待调用结束，并检查 Future 暴露的原始异常类型。
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (ExecutionException expected) {
            Throwable cause = expected.getCause();
            assertTrue("Unexpected failure: " + cause, type.isInstance(cause));
            return cause;
        }
    }

    private static class Host implements AutoCloseable {
        private static final AtomicLong                             REQUEST_IDS  = new AtomicLong();
        final                ServiceDomain<Echo>                    service      = new ServiceDomain<>(Echo.class);
        final                InterAddress                           peer;
        final                Method                                 method;
        final                BlockingQueue<RequestPayload>          sent         = new LinkedBlockingQueue<>();
        final                BlockingQueue<Runnable>                timers       = new LinkedBlockingQueue<>();
        final                BlockingQueue<BasicFuture<MetChannel>> callbacks    = new LinkedBlockingQueue<>();
        final                CountDownLatch                         limitReached = new CountDownLatch(1);
        final                TestConnectorManager                   manager;
        final                MetChannel                             channel;
        final                MetCaller                              caller;
        volatile             SendLimitPolicy                        policy       = SendLimitPolicy.Reject;
        volatile             MetFilter                              filter;
        boolean          rejectTimer;
        boolean          immediateTimeout;
        boolean          failSend;
        boolean          noFilters;
        RuntimeException requestOptionFailure;
        Cancellable      timerCancellation = () -> true;

        @SuppressWarnings("unchecked")
        Host(int maximum) throws Exception {
            // 准备无网络配置的服务与限流参数，按需注入请求选项故障。
            this.peer = new InterAddress("rsf://127.0.0.1:2181/default");
            this.method = Echo.class.getMethod("echo", String.class);
            this.service.setBindName("Echo");
            this.service.setSerializeType("Java");
            this.service.setClientTimeout(6000);
            this.service.setDistributed(true);
            Map<String, String> endpointOptions = Map.of();
            ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Collections.emptyMap());
            InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
            ConnectorConfig endpoint = new ConnectorConfig(//
                    "rpc", local, endpointOptions, Collections.singletonList(protocol), false);
            AtomicReference<List<ConnectorConfig>> configured = new AtomicReference<>(Collections.emptyList());
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
                    case "getMaximumRequest":
                        return maximum;
                    case "getSendLimitPolicy":
                        this.limitReached.countDown();
                        return this.policy;
                    case "getUnitName":
                        return "default";
                    case "getRequestOptions":
                        if (this.requestOptionFailure != null) {
                            throw this.requestOptionFailure;
                        }
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + m);
                }
            });

            // 上下文代理只提供本单元测试真实使用的上下文契约。
            MetContext context = proxy(MetContext.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getApplicationName":
                        return "application";
                    case "getDefaultProtocol":
                        return "rsf";
                    case "getSettings":
                        return settings;
                    case "getClassLoader":
                        return this.getClass().getClassLoader();
                    case "subscribe":
                        return (Cancellable) () -> true;
                    default:
                        throw new AssertionError("Unexpected context access: " + m);
                }
            });

            // 传输替身记录报文，并让测试控制发送 Future 的终态。
            this.channel = proxy(MetChannel.class, (p, m, args) -> {
                if (StringUtils.equals("sendData", m.getName())) {
                    if (this.failSend) {
                        throw new IllegalStateException("send failed");
                    }
                    this.sent.add((RequestPayload) args[0]);
                    BasicFuture<MetChannel> writing = new BasicFuture<>();
                    this.callbacks.add(writing);
                    return writing;
                }
                throw new AssertionError("Unexpected channel access: " + m);
            });

            // 连接直接返回传输替身，调度器保存任务供测试确定性触发。
            this.manager = new TestConnectorManager(context) {
                @Override
                public BasicFuture<MetChannel> connect(//
                        String endpointName, String protocol, InterAddress address) {
                    return new BasicFuture<>(Host.this.channel);
                }

                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    if (Host.this.rejectTimer) {
                        throw new RejectedExecutionException("timer closed");
                    }
                    Host.this.timers.add(task);
                    if (Host.this.immediateTimeout) {
                        task.run();
                    }
                    return Host.this.timerCancellation;
                }
            };
            this.manager.init();
            configured.set(Collections.singletonList(endpoint));

            // 仍由真实调用器执行请求准入、响应分发和资源回收。
            this.caller = new MetCaller(this.manager, id -> {
                if (this.noFilters) {
                    return null;
                }
                if (this.filter == null) {
                    return new Supplier[0];
                }
                return new Supplier[] { (Supplier<MetFilter>) () -> this.filter };
            });
        }

        MetRequestObject request(InterAddress target, Object[] arguments) {
            long requestId = REQUEST_IDS.incrementAndGet();
            MetContext context = this.caller.getContext();
            MetRequestObject request = new MetRequestObject(requestId, true, //
                    context, this.service, this.method, arguments);
            return this.configureRequest(request, target);
        }

        MetRequestObject blockedRequest(CountDownLatch mapping, CountDownLatch resume) {
            // 覆盖公开参数读取契约，在真实报文映射路径中控制线程交错。
            long requestId = REQUEST_IDS.incrementAndGet();
            MetContext context = this.caller.getContext();
            Object[] arguments = { "value" };
            MetRequestObject request = new MetRequestObject(requestId, true, //
                    context, this.service, this.method, arguments) {
                @Override
                public Object[] getParameterObject() {
                    // 告知测试映射已开始，并在有界等待后恢复原始参数读取。
                    mapping.countDown();
                    try {
                        assertTrue(resume.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return super.getParameterObject();
                }
            };
            return this.configureRequest(request, this.peer);
        }

        private MetRequestObject configureRequest(MetRequestObject request, InterAddress target) {
            request.setPeerAddress(target);
            request.setSerializeType(this.service.getSerializeType());
            request.setMessage(this.service.isMessage());
            request.setTimeout(this.service.getClientTimeout());
            return request;
        }

        MetFuture invoke() {
            return this.send(this.request(this.peer, new Object[] { "value" }));
        }

        MetFuture send(MetRequestObject request) {
            return this.caller.invoke(request);
        }

        void respond(MetFuture future, short status) {
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(future.getRequest().getRequestID());
            response.setStatus(status);
            response.setReturnData("done");
            this.manager.deliver(this.channel, response.getRequestID(), response);
        }

        @Override
        public void close() {
            // Host 创建并持有两者；先关闭连接，再释放调用器自己的资源。
            this.caller.stopAdmission();
            try {
                this.manager.close();
            } finally {
                this.caller.close();
            }
        }
    }

    private static class TestConnectorManager extends ConnectorManager {
        private ConnectorSubscriber subscriber;

        private TestConnectorManager(MetContext context) {
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
        ClassLoader loader = type.getClassLoader();
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { type }, handler);
        return type.cast(proxy);
    }
}
