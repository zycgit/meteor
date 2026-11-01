/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolSessionContextTestAccess;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 有序网络和可控调度器验证会话状态；正式网络互通放在 realmeteor 场景。 */
public class OncRpcSessionTest {
    private static final long PROGRAM = 0x2000_0001L;

    @MetService(id = PROGRAM)
    public interface Service {
        @MetMethod(id = 1)
        String echo(String value);
    }

    @Test
    public void connectedCompletesReadyWithoutSendingHandshake() throws Exception {
        // ONC RPC 不增加私有握手或心跳。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            assertFalse(fixture.session.ready().isDone());
            fixture.session.connected();
            fixture.session.connected();
            fixture.session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.scheduler.tasks.isEmpty());
        }
    }

    @Test
    public void sendBeforeConnectedDoesNotReachNetwork() throws Exception {
        // 尚未连接时不得提交 RPC 数据。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            Future<Void> sent = fixture.session.send(request(1));
            assertTrue(sent.getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedKeepsOriginalFailure() throws Exception {
        // 连接失败由 ready 传播，迟到的 connected 不会恢复会话。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            IOException failure = new IOException("connect failed");
            fixture.session.closed(failure);
            fixture.session.connected();
            assertSame(failure, fixture.session.ready().getCause());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void alreadyClosedSessionRejectsNewRequests() throws Exception {
        // 关闭是终态，不保留新请求关联。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            fixture.session.closed(null);
            Future<Void> sent = fixture.session.send(request(2));
            assertTrue(sent.getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void closedSessionDiscardsLateNetworkInput() throws Exception {
        // 迟到的网络数据不能重新交付入站业务。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.closed(null);
            fixture.session.receive(fixture.call(11, "late"));
            assertTrue(fixture.messages.isEmpty());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void sessionNeverClosesBorrowedNetwork() throws Exception {
        // 内核所有者仍管理网络生命周期，会话只释放自己的关联。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(3)).get(1, TimeUnit.SECONDS);
            fixture.session.prepareDrain();
            fixture.session.closed(null);
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void physicalNetworkClosureRejectsNewWrite() throws Exception {
        // 通道已经关闭时不能缓存一个永远无法提交的请求。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            fixture.network.open = false;
            Future<Void> sent = fixture.session.send(request(4));
            assertTrue(sent.getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void unsupportedPayloadDoesNotWrite() throws Exception {
        // 本地失败通知没有独立的 ONC RPC CALL 编码。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            Future<Void> sent = fixture.session.send(new ThrowPayload(new IOException("local failure")));
            assertTrue(sent.getCause() instanceof UnsupportedOperationException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void tcpRequestUsesRecordMarking() throws Exception {
        // 一个 TCP CALL 包含 record marker 和标准 RPC v2 信封。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(10)).get(1, TimeUnit.SECONDS);
            OncRpcMessage message = fixture.outgoing(0);
            assertTrue(message.isCall());
            assertEquals(PROGRAM, message.getProgram());
            assertEquals(1, message.getProcedure());
        }
    }

    @Test
    public void udpRequestDoesNotContainTcpRecordMarker() throws Exception {
        // 数据报直接承载完整 RPC 消息，不带 TCP record marker。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(11)).get(1, TimeUnit.SECONDS);
            OncRpcMessage message = fixture.wire.decode(fixture.network.writes.get(0));
            assertTrue(message.isCall());
            assertEquals(PROGRAM, message.getProgram());
        }
    }

    @Test
    public void writeCompletionDoesNotCompleteOrdinaryRpc() throws Exception {
        // 发送 Future 只表示提交完成，业务结果等待远端 REPLY。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(12);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            assertFalse(request.completion().isDone());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void matchingReplyCompletesWithOriginalLongId() throws Exception {
        // XID 与内核 long 请求号分别维护。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request((1L << 40) + 21);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            fixture.reply(fixture.outgoing(0).getXid(), "result");
            assertEquals(request.getRequestID(), fixture.ids.get(0).longValue());
            assertEquals("result", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void identicalLowBitsOfCoreIdsDoNotCollide() throws Exception {
        // 两个内核 ID 低 32 位一致仍分配独立线上 XID。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            RequestPayload first = request(7);
            RequestPayload second = request((1L << 32) + 7);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            fixture.session.send(second).get(1, TimeUnit.SECONDS);
            long firstXid = fixture.outgoing(0).getXid();
            long secondXid = fixture.outgoing(1).getXid();
            assertNotEquals(firstXid, secondXid);

            // 反序回应恢复各自完整的请求号。
            fixture.reply(secondXid, "second");
            fixture.reply(firstXid, "first");
            assertEquals(second.getRequestID(), fixture.ids.get(0).longValue());
            assertEquals(first.getRequestID(), fixture.ids.get(1).longValue());
        }
    }

    @Test
    public void unknownReplyDoesNotConsumePendingCall() throws Exception {
        // 陌生 XID 的回应不应影响仍在等待的请求。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(22);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            long xid = fixture.outgoing(0).getXid();
            fixture.reply((xid + 99) & 0xffff_ffffL, "unknown");
            assertFalse(request.completion().isDone());
            assertTrue(fixture.messages.isEmpty());

            fixture.reply(xid, "right");
            assertEquals("right", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void repeatedReplyIsDeliveredOnce() throws Exception {
        // UDP 重复回应只完成一次本地 RPC。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(23)).get(1, TimeUnit.SECONDS);
            long xid = fixture.outgoing(0).getXid();
            fixture.reply(xid, "result");
            fixture.reply(xid, "result");
            assertEquals(1, fixture.messages.size());
        }
    }

    @Test
    public void acceptedFailureIsDeliveredToOriginalRequest() throws Exception {
        // 标准失败状态经失败负载交给上层，不伪装成正常返回。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(24);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            long xid = fixture.outgoing(0).getXid();
            OncRpcMessage failure = OncRpcMessage.newAcceptedError( //
                    xid, OncRpcAuth.newNone(), OncRpcMessage.PROC_UNAVAIL, 0, 0);
            fixture.receive(failure);
            assertTrue(fixture.messages.get(0) instanceof ThrowPayload);
            assertEquals(request.getRequestID(), fixture.ids.get(0).longValue());
            assertNotNull(request.completion().getCause());
        }
    }

    @Test
    public void duplicateCoreRequestIdDoesNotReplacePendingCall() throws Exception {
        // 同一内核请求号不得悄悄覆盖先提交的请求。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            RequestPayload first = request(25);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            Future<Void> duplicate = fixture.session.send(request(25));
            assertTrue(duplicate.getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());

            fixture.reply(fixture.outgoing(0).getXid(), "first");
            assertEquals("first", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void outgoingCapacityRejectsAdditionalCall() throws Exception {
        // 到达容量上限时保留活跃调用，不驱逐它来接纳新请求。
        try (Fixture fixture = new Fixture(false, Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            fixture.session.send(request(26)).get(1, TimeUnit.SECONDS);
            Future<Void> second = fixture.session.send(request(27));
            assertTrue(second.getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void rpcCompletionReleasesOutgoingCapacity() throws Exception {
        // 内核已结束请求时立即清除协议关联，下一次调用无需等待定时清理。
        try (Fixture fixture = new Fixture(false, Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload first = request(28);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            first.complete(new TimeoutException("RPC deadline"));
            fixture.session.send(request(29)).get(1, TimeUnit.SECONDS);
            assertEquals(2, fixture.network.writes.size());
        }
    }

    @Test
    public void completedRequestIsNotSubmittedAgain() throws Exception {
        // 已经结束的请求不再分配 XID 或提交网络写入。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(30);
            TimeoutException timeout = new TimeoutException("already finished");
            request.complete(timeout);
            Future<Void> sent = fixture.session.send(request);
            assertSame(timeout, sent.getCause());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void lateReplyAfterCoreTimeoutIsIgnored() throws Exception {
        // 超时后的 UDP 迟到回应不再产生业务通知。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(31);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            long xid = fixture.outgoing(0).getXid();
            request.complete(new TimeoutException("RPC deadline"));
            fixture.reply(xid, "late");
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void failedInitialWritePreservesOriginalCause() throws Exception {
        // 首次写失败不启动重传，也不替换原始异常。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            IOException failure = new IOException("write failed");
            fixture.network.nextWrite = new BasicFuture<>();
            fixture.network.nextWrite.failed(failure);
            RequestPayload request = request(32);
            Future<Void> sent = fixture.session.send(request);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void synchronousWriteFailureIsReported() throws Exception {
        // 传输直接抛异常和异步失败拥有相同的失败边界。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            IllegalStateException failure = new IllegalStateException("write threw");
            fixture.network.writeFailure = failure;
            Future<Void> sent = fixture.session.send(request(33));
            assertSame(failure, sent.getCause());
        }
    }

    @Test
    public void cancelledWriteCancelsItsRequest() throws Exception {
        // 传输取消不能被视为成功提交。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload request = request(34);
            Future<Void> sent = fixture.session.send(request);
            writing.cancel();
            assertTrue(sent.getCause() instanceof CancellationException);
            assertTrue(request.completion().getCause() instanceof CancellationException);
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void cancellingSendCancelsPendingNetworkWrite() throws Exception {
        // 调用方取消尚未提交完成的写入，会话同时释放网络 Future。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload request = request(35);
            Future<Void> sent = fixture.session.send(request);
            sent.cancel();
            assertTrue(writing.isCancelled());
            assertTrue(request.completion().isDone());
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void tcpNeverSchedulesUdpRetransmission() throws Exception {
        // TCP 可靠性由传输保证，会话没有第二套 RPC 定时器。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(40)).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.scheduler.tasks.isEmpty());
        }
    }

    @Test
    public void udpSchedulesFirstRetryAfterSuccessfulSubmission() throws Exception {
        // 首次提交成功后才安排协议重传等待。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(41)).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.scheduler.active().size());
            assertEquals(200, fixture.scheduler.active().get(0).delay);
        }
    }

    @Test
    public void pendingInitialWriteDoesNotStartRetryClock() throws Exception {
        // 正在写出的数据不能被重传任务重复提交。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.network.nextWrite = new BasicFuture<>();
            fixture.session.send(request(42));
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void udpRetryReusesExactXidAndBytes() throws Exception {
        // 重传复用原来的认证、参数和 XID，不重新编码成另一个调用。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(43)).get(1, TimeUnit.SECONDS);
            byte[] original = fixture.network.writes.get(0).clone();
            fixture.scheduler.fireNext();
            assertArrayEquals(original, fixture.network.writes.get(1));
            assertEquals(fixture.outgoing(0).getXid(), fixture.outgoing(1).getXid());
        }
    }

    @Test
    public void retryDelayDoublesAfterEachMissingReply() throws Exception {
        // 指数退避从 200 毫秒递增，调度器只记录而不真实等待。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(44)).get(1, TimeUnit.SECONDS);
            fixture.scheduler.fireNext();
            assertEquals(400, fixture.scheduler.active().get(0).delay);
            fixture.scheduler.fireNext();
            assertEquals(800, fixture.scheduler.active().get(0).delay);
        }
    }

    @Test
    public void retryDelayStopsAtConfiguredMaximum() throws Exception {
        // 退避上限独立于最终 RPC 超时，超过上限仍使用固定最大间隔。
        try (Fixture fixture = new Fixture(true, Map.of("retryAttempts", "8", "retryMaxDelay", "500"))) {
            fixture.session.connected();
            fixture.session.send(request(45)).get(1, TimeUnit.SECONDS);
            fixture.scheduler.fireNext();
            fixture.scheduler.fireNext();
            assertEquals(500, fixture.scheduler.active().get(0).delay);
        }
    }

    @Test
    public void retryExhaustionReportsTransportFailureOnce() throws Exception {
        // 协议重传耗尽属于传输失败，不冒充内核 RPC deadline。
        try (Fixture fixture = new Fixture(true, Map.of("retryAttempts", "2"))) {
            fixture.session.connected();
            RequestPayload request = request(46);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            fixture.scheduler.fireAll(8);
            Throwable cause = request.completion().getCause();
            assertTrue(cause instanceof IOException);
            assertFalse(cause instanceof TimeoutException);
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.messages.get(0) instanceof ThrowPayload);
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void successfulReplyCancelsScheduledRetry() throws Exception {
        // 合法回应到达后撤销尚未执行的 UDP 定时任务。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(47)).get(1, TimeUnit.SECONDS);
            Scheduled retry = fixture.scheduler.active().get(0);
            fixture.reply(fixture.outgoing(0).getXid(), "done");
            assertTrue(retry.cancelled);
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void coreTimeoutCancelsScheduledRetry() throws Exception {
        // 内核 deadline 比重传先结束时，由 completion 释放协议调度句柄。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(48);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            Scheduled retry = fixture.scheduler.active().get(0);
            request.complete(new TimeoutException("RPC deadline"));
            assertTrue(retry.cancelled);
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void cancelledCoreRequestCancelsScheduledRetry() throws Exception {
        // 用户取消已提交调用时不再在网络后台重复发送。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(49);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            Scheduled retry = fixture.scheduler.active().get(0);
            request.completion().cancel();
            assertTrue(retry.cancelled);
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void staleRetryCallbackCannotSendAfterReply() throws Exception {
        // 即使任务取消与执行发生竞争，旧回调也会先检查调用身份。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(50)).get(1, TimeUnit.SECONDS);
            Scheduled retry = fixture.scheduler.active().get(0);
            fixture.reply(fixture.outgoing(0).getXid(), "done");
            retry.action.run();
            assertEquals(1, fixture.network.writes.size());
            assertEquals(1, fixture.messages.size());
        }
    }

    @Test
    public void failedRetryWriteStopsFurtherRetries() throws Exception {
        // 重传本身失败时保留原始异常，不继续无限调度。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload request = request(51);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            IOException failure = new IOException("retry write failed");
            fixture.network.nextWrite = new BasicFuture<>();
            fixture.network.nextWrite.failed(failure);
            fixture.scheduler.fireNext();
            assertSame(failure, request.completion().getCause());
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void retryExhaustionReleasesPendingCapacity() throws Exception {
        // 失败调用不能永久占用出站容量。
        try (Fixture fixture = new Fixture(true, Map.of("maxPendingRequests", "1", "retryAttempts", "1"))) {
            fixture.session.connected();
            fixture.session.send(request(52)).get(1, TimeUnit.SECONDS);
            fixture.scheduler.fireAll(8);
            fixture.session.send(request(53)).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.scheduler.active().size());
        }
    }

    @Test
    public void incomingCallUsesLocalIdSeparateFromWireXid() throws Exception {
        // 服务端为每次业务派发创建本地身份，线上无符号 XID 原样保留给回应。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(0xffff_ffffL, "hello"));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            assertNotEquals(0xffff_ffffL, incoming.getRequestID());
            fixture.session.send(response(incoming.getRequestID(), "reply")).get(1, TimeUnit.SECONDS);
            assertEquals(0xffff_ffffL, fixture.outgoing(0).getXid());
        }
    }

    @Test
    public void duplicateActiveDatagramIsNotDispatchedAgain() throws Exception {
        // 相同 XID 和完整正文的 UDP 重发只保留最初的业务执行。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            byte[] call = fixture.call(61, "same");
            fixture.session.receive(call);
            fixture.session.receive(call.clone());
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void completedDatagramReplayUsesCachedExactReply() throws Exception {
        // 完成记录缓存原始回应字节，丢包后重发不重新执行服务。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            byte[] call = fixture.call(62, "same");
            fixture.session.receive(call);
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(incoming.getRequestID(), "result")).get(1, TimeUnit.SECONDS);
            byte[] originalReply = fixture.network.writes.get(0).clone();

            fixture.session.receive(call);
            assertEquals(1, fixture.messages.size());
            assertArrayEquals(originalReply, fixture.network.writes.get(1));
        }
    }

    @Test
    public void conflictingActiveXidCannotReplaceOriginalCall() throws Exception {
        // 活跃 XID 的不同参数不是重传，拒绝新正文并保留原调用。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(63, "first"));
            RequestPayload original = (RequestPayload) fixture.messages.get(0);
            fixture.session.receive(fixture.call(63, "conflict"));
            assertEquals(1, fixture.messages.size());
            assertEquals(OncRpcMessage.SYSTEM_ERR, fixture.outgoing(0).getAcceptStatus());

            fixture.session.send(response(original.getRequestID(), "first-result")).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.outgoing(1).isSuccess());
        }
    }

    @Test
    public void activeDuplicateCacheSlotCannotBeEvicted() throws Exception {
        // 缓存满且唯一记录仍活跃时，不驱逐旧身份来重复执行业务。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheSize", "1"))) {
            fixture.session.connected();
            byte[] first = fixture.call(64, "first");
            fixture.session.receive(first);
            fixture.session.receive(fixture.call(65, "second"));
            fixture.session.receive(first);
            assertEquals(1, fixture.messages.size());
            assertEquals(OncRpcMessage.SYSTEM_ERR, fixture.outgoing(0).getAcceptStatus());
        }
    }

    @Test
    public void completedCacheEntryCanBeReplacedAtCapacity() throws Exception {
        // 容量约束允许淘汰已完成记录，不能挡住所有后续调用。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheSize", "1"))) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(66, "first"));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(incoming.getRequestID(), "reply")).get(1, TimeUnit.SECONDS);
            fixture.session.receive(fixture.call(67, "second"));
            assertEquals(2, fixture.messages.size());
        }
    }

    @Test
    public void completedXidCanBeReusedWithDifferentArguments() throws Exception {
        // 已完成 XID 的新正文视为新调用，不重放另一个请求的旧结果。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(68, "first"));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(incoming.getRequestID(), "reply")).get(1, TimeUnit.SECONDS);
            fixture.session.receive(fixture.call(68, "new"));
            assertEquals(2, fixture.messages.size());
            RequestPayload replacement = (RequestPayload) fixture.messages.get(1);
            assertEquals("new", replacement.getParameterValues().get(0));
            assertNotEquals(incoming.getRequestID(), replacement.getRequestID());
        }
    }

    @Test
    public void unknownResponseIdDoesNotWriteAnything() throws Exception {
        // 旧业务返回不能误用当前唯一的入站调用。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(69, "pending"));
            Future<Void> sent = fixture.session.send(response(99999, "late"));
            assertTrue(sent.getCause() instanceof IllegalStateException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void sameWireXidInBothDirectionsDoesNotCollide() throws Exception {
        // 双向 CALL 的 XID 命名空间互相独立。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(70);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            long xid = fixture.outgoing(0).getXid();
            fixture.session.receive(fixture.call(xid, "incoming"));
            RequestPayload incoming = (RequestPayload) fixture.messages.remove(0);
            fixture.session.send(response(incoming.getRequestID(), "incoming-result")).get(1, TimeUnit.SECONDS);
            fixture.reply(xid, "outgoing-result");
            assertEquals(outgoing.getRequestID(), fixture.ids.get(fixture.ids.size() - 1).longValue());
            assertEquals("outgoing-result", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void tcpRecordWaitsForItsFinalByte() throws Exception {
        // Record marker 和正文均可逐字节分片，完整前不派发。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.networkBytes(fixture.call(80, "split"));
            for (int index = 0; index < bytes.length - 1; index++) {
                fixture.session.receive(new byte[] { bytes[index] });
                assertTrue(fixture.messages.isEmpty());
            }
            fixture.session.receive(new byte[] { bytes[bytes.length - 1] });
            assertEquals(1, fixture.messages.size());
        }
    }

    @Test
    public void tcpMultipleRecordsInOneReadDispatchSeparately() throws Exception {
        // 单次 TCP 读取可以携带连续的完整 record。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(fixture.networkBytes(fixture.call(81, "first")));
            bytes.write(fixture.networkBytes(fixture.call(82, "second")));
            fixture.session.receive(bytes.toByteArray());
            assertEquals(2, fixture.messages.size());
        }
    }

    @Test
    public void tcpRpcMessageMaySpanSeveralRecordFragments() throws Exception {
        // 多个 record fragments 拼成一个 CALL，只派发一次。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            byte[] bytes = OncRpcRecordDecoder.encodeRecord(fixture.call(83, "fragments"), 3);
            fixture.session.receive(bytes);
            assertEquals(1, fixture.messages.size());
            assertEquals("fragments", ((RequestPayload) fixture.messages.get(0)).getParameterValues().get(0));
        }
    }

    @Test
    public void outgoingTcpFragmentSizeIsRespected() throws Exception {
        // 出站配置只控制 record 切片，不改变最终 RPC 正文。
        try (Fixture fixture = new Fixture(false, Map.of("recordFragmentSize", "8"))) {
            fixture.session.connected();
            fixture.session.send(request(84)).get(1, TimeUnit.SECONDS);
            byte[] wire = fixture.network.writes.get(0);
            int markerLength = wire[3] & 0xff;
            assertEquals(8, markerLength);
            assertTrue(fixture.outgoing(0).isCall());
        }
    }

    @Test
    public void malformedUdpPacketDoesNotPoisonNextDatagram() throws Exception {
        // 独立数据报的畸形正文被丢弃，不跨包累积损坏状态。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(new byte[] { 1, 2, 3 });
            fixture.session.receive(fixture.call(85, "next"));
            assertEquals(1, fixture.messages.size());
            assertEquals("next", ((RequestPayload) fixture.messages.get(0)).getParameterValues().get(0));
        }
    }

    @Test
    public void invalidTcpRecordMarkerFailsProtocol() throws Exception {
        // 有序 TCP 长度失真后无法重新确定消息边界，交由外层关闭连接。
        try (Fixture fixture = new Fixture(false, Map.of("maxMessageSize", "128"))) {
            fixture.session.connected();
            byte[] oversized = new byte[] { (byte) 0x80, 0, 1, 0 };
            assertThrows(Exception.class, () -> fixture.session.receive(oversized));
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void nullProcedureRespondsWithoutBusinessDispatch() throws Exception {
        // 标准 NULL 探测在协议层返回 void，不触发应用业务。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            OncRpcMessage call = callMessage(90, PROGRAM, 1, 0, new byte[0]);
            fixture.receive(call);
            OncRpcMessage reply = fixture.outgoing(0);
            assertTrue(reply.isSuccess());
            assertEquals(0, reply.getBody().length);
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void unsupportedProgramReceivesStandardError() throws Exception {
        // 未发布的程序号获得 PROG_UNAVAIL，而不是错误派发到现有服务。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.receive(callMessage(91, PROGRAM + 1, 1, 1, text("bad")));
            assertEquals(OncRpcMessage.PROG_UNAVAIL, fixture.outgoing(0).getAcceptStatus());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void unsupportedVersionReceivesVersionRange() throws Exception {
        // 程序存在但版本不匹配时保留服务可用版本范围。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.receive(callMessage(92, PROGRAM, 3, 1, text("bad")));
            OncRpcMessage reply = fixture.outgoing(0);
            assertEquals(OncRpcMessage.PROG_MISMATCH, reply.getAcceptStatus());
            assertEquals(1, reply.getLowVersion());
            assertEquals(1, reply.getHighVersion());
        }
    }

    @Test
    public void unsupportedProcedureReceivesStandardError() throws Exception {
        // 不存在的过程号不能回退到同名或唯一方法。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.receive(callMessage(93, PROGRAM, 1, 99, new byte[0]));
            assertEquals(OncRpcMessage.PROC_UNAVAIL, fixture.outgoing(0).getAcceptStatus());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void malformedArgumentsReceiveGarbageArgs() throws Exception {
        // 信封正确但参数 XDR 缺失时返回 GARBAGE_ARGS。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.receive(callMessage(94, PROGRAM, 1, 1, new byte[0]));
            assertEquals(OncRpcMessage.GARBAGE_ARGS, fixture.outgoing(0).getAcceptStatus());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void unsupportedRpcVersionReceivesMismatch() throws Exception {
        // RPC 版本校验与业务程序版本分开处理。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            OncRpcMessage call = OncRpcMessage.newCall(95, 3, PROGRAM, 1, 1, //
                    OncRpcAuth.newNone(), OncRpcAuth.newNone(), text("bad"));
            fixture.receive(call);
            OncRpcMessage reply = fixture.outgoing(0);
            assertEquals(OncRpcMessage.RPC_MISMATCH, reply.getRejectStatus());
            assertEquals(2, reply.getLowVersion());
            assertEquals(2, reply.getHighVersion());
        }
    }

    @Test
    public void unsupportedCredentialsReceiveAuthError() throws Exception {
        // 未支持的认证 flavor 不会被当成匿名调用。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            OncRpcMessage call = OncRpcMessage.newCall(96, 2, PROGRAM, 1, 1, //
                    new OncRpcAuth(77, new byte[0]), OncRpcAuth.newNone(), text("bad"));
            fixture.receive(call);
            assertEquals(OncRpcMessage.AUTH_ERROR, fixture.outgoing(0).getRejectStatus());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void errorReplyWriteFailureDoesNotCloseBorrowedNetwork() throws Exception {
        // 错误回执写失败结束会话关联，网络关闭仍归外层所有者。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.network.nextWrite = new BasicFuture<>();
            IOException failure = new IOException("error reply failed");
            fixture.network.nextWrite.failed(failure);
            fixture.receive(callMessage(97, PROGRAM + 1, 1, 1, text("bad")));
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void prepareDrainRejectsNewOutgoingRequest() throws Exception {
        // 排空开始后拒绝新写入，已提交的写入交给资源所有者排空。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.connected();
            fixture.session.prepareDrain();
            Future<Void> sent = fixture.session.send(request(100));
            assertTrue(sent.getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void prepareDrainRejectsNewIncomingCall() throws Exception {
        // 排空中的会话不再派发新业务执行。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.prepareDrain();
            fixture.session.receive(fixture.call(101, "new"));
            assertTrue(fixture.messages.isEmpty());
            assertEquals(OncRpcMessage.SYSTEM_ERR, fixture.outgoing(0).getAcceptStatus());
        }
    }

    @Test
    public void prepareDrainReleasesIncomingCall() throws Exception {
        // 尚未形成回应的业务调用不无限阻止连接排空。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(102, "pending"));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.prepareDrain();
            assertTrue(incoming.completion().isDone());
            assertEquals(OncRpcMessage.SYSTEM_ERR, fixture.outgoing(0).getAcceptStatus());
        }
    }

    @Test
    public void prepareDrainStopsUdpRetries() throws Exception {
        // 排空后不会继续向外层已经锁写的通道提交重传。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.send(request(103)).get(1, TimeUnit.SECONDS);
            Scheduled retry = fixture.scheduler.active().get(0);
            fixture.session.prepareDrain();
            assertTrue(retry.cancelled);
            retry.action.run();
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void repeatedDrainDoesNotSendDuplicateFailureReply() throws Exception {
        // 重复生命周期通知保持幂等。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.call(104, "pending"));
            fixture.session.prepareDrain();
            fixture.session.prepareDrain();
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void closedSessionFailsPendingCallOnce() throws Exception {
        // 先摘除关联再通知，关闭回调重入不会重复报告失败。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(105);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            IOException failure = new IOException("peer closed");
            outgoing.completion().onFinal(done -> fixture.session.closed(failure));
            fixture.session.closed(failure);
            fixture.session.closed(failure);
            assertSame(failure, outgoing.completion().getCause());
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void closeCancelsUnfinishedNetworkWrite() throws Exception {
        // 会话关闭释放自己提交的 Future，不直接关闭借用通道。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(106);
            Future<Void> sent = fixture.session.send(outgoing);
            fixture.session.closed(new IOException("peer closed"));
            assertTrue(writing.isCancelled());
            assertTrue(sent.isDone());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void coreCompletedBeforeCloseDoesNotReceiveAnotherFailure() throws Exception {
        // RPC 已完成但有序清理尚未执行时，关闭不得再次通知上层。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(107);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            fixture.network.deferCallbacks = true;
            TimeoutException timeout = new TimeoutException("already expired");
            outgoing.complete(timeout);
            fixture.session.closed(new IOException("closed later"));
            fixture.network.runCallbacks();
            assertSame(timeout, outgoing.completion().getCause());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void completedCacheEntryExpiresAfterConfiguredTtl() throws Exception {
        // 此处专门观察缓存时效，等待窗口不承担异步完成信号的职责。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheTtl", "1"))) {
            fixture.session.connected();
            byte[] call = fixture.call(110, "same");
            fixture.session.receive(call);
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(incoming.getRequestID(), "result")).get(1, TimeUnit.SECONDS);
            Thread.sleep(20);

            // 已过期的完成记录不再重放，重复正文成为新调用。
            fixture.session.receive(call);
            assertEquals(2, fixture.messages.size());
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void activeCacheEntryDoesNotExpireByReplyTtl() throws Exception {
        // TTL 只限制已完成回执，活跃业务身份不能到期后重复执行。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheTtl", "1"))) {
            fixture.session.connected();
            byte[] call = fixture.call(111, "active");
            fixture.session.receive(call);
            Thread.sleep(20);
            fixture.session.receive(call);
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void retryAttemptsZeroDisablesUdpRetryScheduling() throws Exception {
        // 显式禁用协议重传后，调用只受原有内核 deadline 管理。
        try (Fixture fixture = new Fixture(true, Map.of("retryAttempts", "0"))) {
            fixture.session.connected();
            RequestPayload request = request(112);
            fixture.session.send(request).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.scheduler.tasks.isEmpty());
            assertFalse(request.completion().isDone());
        }
    }

    @Test
    public void replyBeforeWriteCallbackCompletesSubmittedFuture() throws Exception {
        // 对端回应比本地写完成通知更早时，合法回应本身证明 CALL 已发送。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload request = request(113);
            Future<Void> sent = fixture.session.send(request);
            fixture.reply(fixture.outgoing(0).getXid(), "result");
            sent.get(1, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.scheduler.active().isEmpty());
        }
    }

    @Test
    public void lateWriteFailureCannotReplaceSuccessfulReply() throws Exception {
        // 已交付回应后迟到的写失败只清理旧写入，不再通知新的 RPC 失败。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload request = request(114);
            Future<Void> sent = fixture.session.send(request);
            fixture.reply(fixture.outgoing(0).getXid(), "result");
            writing.failed(new IOException("late write callback"));
            assertNull(sent.getCause());
            assertNull(request.completion().getCause());
            assertEquals(1, fixture.messages.size());
        }
    }

    @Test
    public void prepareDrainBeforeConnectedCompletesReadyWithFailure() throws Exception {
        // 尚未建连的会话开始排空后，ready 不能永久等待。
        try (Fixture fixture = new Fixture(false, Map.of())) {
            fixture.session.prepareDrain();
            fixture.session.connected();
            assertTrue(fixture.session.ready().getCause() instanceof IOException);
        }
    }

    @Test
    public void prepareDrainKeepsAlreadySubmittedWriteForOuterDrain() throws Exception {
        // 排空只拒绝后续写入，已提交字节仍由外层传输负责写完。
        try (Fixture fixture = new Fixture(true, Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            fixture.session.send(request(115));
            fixture.session.prepareDrain();
            assertFalse(writing.isCancelled());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void pendingReplyWriteCannotBeEvictedForAnotherIncomingCall() throws Exception {
        // 容量为一且首个回应仍在写出，缓存身份必须保留。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheSize", "1"))) {
            fixture.session.connected();
            byte[] first = fixture.call(116, "first");
            fixture.session.receive(first);
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            fixture.session.send(response(incoming.getRequestID(), "result"));

            // 新调用被拒绝，原调用重发仍只重放回应而不重复派发。
            fixture.session.receive(fixture.call(117, "second"));
            assertEquals(OncRpcMessage.SYSTEM_ERR, fixture.outgoing(1).getAcceptStatus());
            fixture.session.receive(first);
            assertEquals(1, fixture.messages.size());
            assertArrayEquals(fixture.network.writes.get(0), fixture.network.writes.get(2));
            assertFalse(writing.isDone());
        }
    }

    @Test
    public void replyCacheTtlStartsAfterAllSubmittedWritesComplete() throws Exception {
        // 回应写出前经过 TTL 不得删除缓存，所有重复写入都属于同一活跃身份。
        try (Fixture fixture = new Fixture(true, Map.of("duplicateCacheTtl", "1"))) {
            fixture.session.connected();
            byte[] call = fixture.call(118, "same");
            fixture.session.receive(call);
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            BasicFuture<Void> firstWrite = new BasicFuture<>();
            fixture.network.nextWrite = firstWrite;
            fixture.session.send(response(incoming.getRequestID(), "result"));
            Thread.sleep(20);
            BasicFuture<Void> duplicateWrite = new BasicFuture<>();
            fixture.network.nextWrite = duplicateWrite;
            fixture.session.receive(call);
            firstWrite.completed(null);
            Thread.sleep(20);
            fixture.session.receive(call);
            assertEquals(1, fixture.messages.size());

            // 最后一个未完成的重复写入结束后，才开始计算回执缓存时效。
            duplicateWrite.completed(null);
            Thread.sleep(20);
            fixture.session.receive(call);
            assertEquals(2, fixture.messages.size());
        }
    }

    private static RequestPayload request(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceName(Service.class.getName());
        request.setServiceGroup("default");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.setSerializeType("oncrpc");
        request.addParameter(String.class.getName(), "hello");
        return request;
    }

    private static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnType(String.class.getName());
        response.setReturnData(value);
        return response;
    }

    private static byte[] text(String value) {
        XdrOutput output = new XdrOutput(4096, 1024, 16);
        output.writeString(value, 1024);
        return output.toByteArray();
    }

    private static OncRpcMessage callMessage(long xid, long program, long version, long procedure, byte[] body) {
        return OncRpcMessage.newCall(xid, 2, program, version, procedure, //
                OncRpcAuth.newNone(), OncRpcAuth.newNone(), body);
    }

    private static final class Fixture implements AutoCloseable {
        private final MetContext      context;
        private final MemoryNetwork   network   = new MemoryNetwork();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final List<Payload>   messages  = new ArrayList<>();
        private final List<Long>      ids       = new ArrayList<>();
        private final OncRpcWireCodec wire      = new OncRpcWireCodec(8 * 1024 * 1024);
        private final OncRpcSession   session;
        private final boolean         datagram;

        private Fixture(boolean datagram, Map<String, String> options) throws Exception {
            // 真实容器只提供服务元信息，不启动监听，不绕过会话执行业务。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
            configuration.connector("tcp").protocol("oncrpc");
            this.context = configuration.buildContext();
            this.context.publisher().rsfService(Service.class, value -> value) //
                    .bindResource("oncrpc", new OncRpcServiceResource(Service.class)).register();
            this.datagram = datagram;
            ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", "oncrpc", options);
            String endpointName = this.context.getSettings().getConnectorConfigs().iterator().next().name();
            RouteMountIndex routes = new RouteMountIndex(endpointName, config);
            ProtocolSessionContext protocol = ProtocolSessionContextTestAccess.newContext( //
                    this.context, this.scheduler::schedule, null, true, routes);
            this.session = new OncRpcSession(config, protocol, this.network, (payload, id) -> {
                this.messages.add(payload);
                this.ids.add(id);
            }, datagram);
        }

        private byte[] call(long xid, String value) {
            return this.wire.encode(callMessage(xid, PROGRAM, 1, 1, text(value)));
        }

        private byte[] networkBytes(byte[] message) {
            if (this.datagram) {
                return message;
            }
            return OncRpcRecordDecoder.encodeRecord(message, 65536);
        }

        private void receive(OncRpcMessage message) throws Exception {
            this.session.receive(this.networkBytes(this.wire.encode(message)));
        }

        private void reply(long xid, String value) throws Exception {
            this.receive(OncRpcMessage.newSuccess(xid, OncRpcAuth.newNone(), text(value)));
        }

        private OncRpcMessage outgoing(int index) {
            byte[] bytes = this.network.writes.get(index);
            if (!this.datagram) {
                OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(8 * 1024 * 1024, 1024);
                List<byte[]> records = decoder.feed(bytes);
                assertEquals(1, records.size());
                bytes = records.get(0);
            }
            return this.wire.decode(bytes);
        }

        @Override
        public void close() throws Exception {
            this.session.closed(null);
            this.context.close();
        }
    }

    private static final class MemoryNetwork implements NetworkChannel<byte[]> {
        private final List<byte[]>      writes    = new ArrayList<>();
        private final List<Runnable>    callbacks = new ArrayList<>();
        private       BasicFuture<Void> nextWrite;
        private       RuntimeException  writeFailure;
        private       boolean           deferCallbacks;
        private       boolean           open      = true;
        private       int               closes;

        @Override
        public InterAddress getLocal() {
            return null;
        }

        @Override
        public InterAddress getRemote() {
            return null;
        }

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public Future<Void> write(byte[] message) {
            if (this.writeFailure != null) {
                throw this.writeFailure;
            }
            this.writes.add(message.clone());
            BasicFuture<Void> result = this.nextWrite;
            this.nextWrite = null;
            return result == null ? new BasicFuture<>((Void) null) : result;
        }

        @Override
        public void execute(Runnable task) {
            if (this.deferCallbacks) {
                this.callbacks.add(task);
            } else {
                task.run();
            }
        }

        private void runCallbacks() {
            this.deferCallbacks = false;
            while (!this.callbacks.isEmpty()) {
                this.callbacks.remove(0).run();
            }
        }

        @Override
        public Future<Void> close() {
            this.open = false;
            this.closes++;
            return new BasicFuture<>((Void) null);
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }

    private static final class ManualScheduler {
        private final List<Scheduled> tasks = new ArrayList<>();

        private Cancellable schedule(Runnable action, long delay) {
            Scheduled scheduled = new Scheduled(action, delay);
            this.tasks.add(scheduled);
            return scheduled;
        }

        private List<Scheduled> active() {
            List<Scheduled> active = new ArrayList<>();
            for (Scheduled task : this.tasks) {
                if (!task.cancelled && !task.fired) {
                    active.add(task);
                }
            }
            return active;
        }

        private void fireNext() {
            List<Scheduled> active = this.active();
            assertFalse(active.isEmpty(), "Expected an active retry task");
            Scheduled task = active.get(0);
            task.fired = true;
            task.action.run();
        }

        private void fireAll(int maximum) {
            for (int index = 0; index < maximum && !this.active().isEmpty(); index++) {
                this.fireNext();
            }
            assertTrue(this.active().isEmpty(), "Retry loop did not terminate within its bound");
        }
    }

    private static final class Scheduled implements Cancellable {
        private final Runnable action;
        private final long     delay;
        private       boolean  cancelled;
        private       boolean  fired;

        private Scheduled(Runnable action, long delay) {
            this.action = action;
            this.delay = delay;
        }

        @Override
        public boolean cancel() {
            if (this.cancelled || this.fired) {
                return false;
            }
            this.cancelled = true;
            return true;
        }
    }
}
