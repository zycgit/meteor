/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.annotation.MetMessage;
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
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.codec.ThriftMessage;
import net.hasor.meteor.protocol.thrift.codec.ThriftWireCodec;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessageType;
import org.junit.Test;
import static org.junit.Assert.*;

/** 可控串行网络验证 TCP 生命周期，真实 SDK 互通由 realmeteor 场景覆盖。 */
public class ThriftSessionTest {
    public interface Service {
        String echo(String value);

        void ping();

        String fail(String reason) throws DeclaredFailure;

        @MetMessage
        void notify(String value);
    }

    @Test
    public void connectedCompletesReadyWithoutAHandshakeOrTimer() throws Exception {
        // Thrift 建连即可收发，重复通知不会增加协议流量。
        try (Fixture fixture = new Fixture(Map.of())) {
            assertFalse(fixture.session.ready().isDone());
            fixture.session.connected();
            fixture.session.connected();
            fixture.session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void requestBeforeConnectedIsRejected() throws Exception {
        // 未建连的请求不会先行交给网络。
        try (Fixture fixture = new Fixture(Map.of())) {
            assertTrue(fixture.session.send(request(1)).getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedPreservesItsOriginalCause() throws Exception {
        // 关闭由资源所有者通知，会话不反向关闭借用网络。
        try (Fixture fixture = new Fixture(Map.of())) {
            IOException failure = new IOException("connect failed");
            fixture.session.closed(failure);
            fixture.session.connected();
            assertSame(failure, fixture.session.ready().getCause());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void framedRequestWaitsForTheLastFragment() throws Exception {
        // 四字节长度与正文均允许被网络逐字节拆开。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.incoming(request(1), 17);
            for (int index = 0; index < bytes.length - 1; index++) {
                fixture.session.receive(new byte[] { bytes[index] });
                assertTrue(fixture.messages.isEmpty());
            }

            // 最后一个字节到达后才分发完整参数。
            fixture.session.receive(new byte[] { bytes[bytes.length - 1] });
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            assertEquals("hello", incoming.getParameterValues().get(0));
        }
    }

    @Test
    public void unframedCoalescedCallsAreDispatchedIndependently() throws Exception {
        // 未分帧 TCP 依赖结构终点识别连续消息。
        try (Fixture fixture = new Fixture(Map.of("framed", "false"))) {
            fixture.session.connected();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(fixture.incoming(request(1), 21));
            bytes.write(fixture.incoming(request(2), 22));
            fixture.session.receive(bytes.toByteArray());

            // 各入站调用拥有独立的内核身份。
            assertEquals(2, fixture.messages.size());
            long first = ((RequestPayload) fixture.messages.get(0)).getRequestID();
            long second = ((RequestPayload) fixture.messages.get(1)).getRequestID();
            assertNotEquals(first, second);
        }
    }

    @Test
    public void longRequestIdsWithIdenticalLowBitsRemainIndependent() throws Exception {
        // 两个 long ID 的低 32 位相同，线上序号仍必须各自分配。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload first = request(7);
            RequestPayload second = request((1L << 32) + 7);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            fixture.session.send(second).get(1, TimeUnit.SECONDS);
            ThriftMessage firstWire = fixture.outgoing(0);
            ThriftMessage secondWire = fixture.outgoing(1);
            assertNotEquals(firstWire.sequenceId(), secondWire.sequenceId());

            // 反序回应恢复完整的内核 ID，不能相互覆盖。
            fixture.reply(second, secondWire, "second");
            fixture.reply(first, firstWire, "first");
            assertEquals(second.getRequestID(), fixture.ids.get(0).longValue());
            assertEquals(first.getRequestID(), fixture.ids.get(1).longValue());
        }
    }

    @Test
    public void ordinaryCallWaitsForItsBusinessResponse() throws Exception {
        // 网络写完只完成发送，不产生普通调用的 Accept。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(31);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            assertFalse(outgoing.completion().isDone());
            assertTrue(fixture.messages.isEmpty());

            // 回应完成业务调用并保留返回值。
            fixture.reply(outgoing, fixture.outgoing(0), "reply");
            assertEquals("reply", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
            assertTrue(outgoing.completion().isDone());
        }
    }

    @Test
    public void wrongMethodReplyDoesNotConsumeThePendingCall() throws Exception {
        // 同 seqid 的错误方法回应必须隔离，随后合法回应仍可完成原调用。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(32);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ThriftMessage sent = fixture.outgoing(0);
            byte[] wrong = fixture.wire.encode("binary", "ping", TMessageType.REPLY, //
                    sent.sequenceId(), new InteropService.ping_result());
            fixture.session.receive(fixture.networkBytes(wrong));
            assertFalse(outgoing.completion().isDone());
            assertTrue(fixture.messages.isEmpty());

            fixture.reply(outgoing, sent, "right");
            assertEquals("right", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void unknownSequenceReplyDoesNotConsumeAnotherCall() throws Exception {
        // 不存在的线上 seqid 不能误用唯一的活跃请求。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(33);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ThriftMessage sent = fixture.outgoing(0);
            byte[] unknown = fixture.wire.encode("binary", "echo", TMessageType.REPLY, //
                    sent.sequenceId() + 1, new InteropService.echo_result().setSuccess("unknown"));
            fixture.session.receive(fixture.networkBytes(unknown));
            assertFalse(outgoing.completion().isDone());
            assertTrue(fixture.messages.isEmpty());

            fixture.reply(outgoing, sent, "right");
            assertEquals("right", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void wrongMessageTypeCannotCompleteAnOutgoingCall() throws Exception {
        // 错误的 ONEWAY echo 不能被当作出站 echo 的回应。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(34);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ThriftMessage sent = fixture.outgoing(0);
            byte[] wrong = fixture.wire.encode("binary", "echo", TMessageType.ONEWAY, //
                    sent.sequenceId(), new InteropService.echo_args("wrong"));
            fixture.session.receive(fixture.networkBytes(wrong));
            assertFalse(outgoing.completion().isDone());
            assertTrue(fixture.messages.isEmpty());
            assertEquals(1, fixture.network.writes.size());

            fixture.reply(outgoing, sent, "right");
            assertEquals("right", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void matchingSequenceIdsInBothDirectionsRemainIndependent() throws Exception {
        // 对端与本端同时发起同 seqid 的普通调用。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(35);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ThriftMessage sent = fixture.outgoing(0);
            fixture.session.receive(fixture.incoming(request(36), sent.sequenceId()));
            RequestPayload incoming = (RequestPayload) fixture.messages.remove(0);

            // 入站应答保留 wire seqid，出站关联仍等待自己的回应。
            fixture.session.send(response(incoming.getRequestID(), "incoming")).get(1, TimeUnit.SECONDS);
            assertEquals(sent.sequenceId(), fixture.outgoing(1).sequenceId());
            fixture.reply(outgoing, sent, "outgoing");
            assertEquals("outgoing", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void rpcTimeoutReleasesCapacityAndDiscardsItsLateReply() throws Exception {
        // 唯一槽位由本地 completion 释放，不添加另一套协议超时器。
        try (Fixture fixture = new Fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload first = request(41);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            ThriftMessage firstWire = fixture.outgoing(0);
            first.complete(new TimeoutException("RPC expired"));
            RequestPayload second = request(42);
            fixture.session.send(second).get(1, TimeUnit.SECONDS);

            // 旧回应不能进入上层或结束后续请求。
            fixture.reply(first, firstWire, "late");
            assertTrue(fixture.messages.isEmpty());
            assertFalse(second.completion().isDone());
            fixture.reply(second, fixture.outgoing(1), "second");
            assertEquals("second", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void rpcCompletionCancelsAQueuedWrite() throws Exception {
        // 请求在网络写出之前结束，写句柄需要同步释放。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(43);
            Future<Void> sent = fixture.session.send(outgoing);
            TimeoutException failure = new TimeoutException("expired before write");
            outgoing.complete(failure);

            // 取消不产生消息确认，发送者保留 RPC 的原始失败。
            assertTrue(writing.isCancelled());
            assertSame(failure, sent.getCause());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void queuedWriteFailureAfterRpcCompletionIsDiscarded() throws Exception {
        // 写失败回调已排队，但 RPC 在其执行之前先超时。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(431);
            Future<Void> sent = fixture.session.send(outgoing);
            fixture.network.deferCallbacks = true;
            writing.failed(new IOException("late write failure"));
            TimeoutException timeout = new TimeoutException("RPC already expired");
            outgoing.complete(timeout);

            // 旧写失败不能再次上抛，发送者保留先发生的 RPC 结束原因。
            fixture.network.runCallbacks();
            assertSame(timeout, sent.getCause());
            assertSame(timeout, outgoing.completion().getCause());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void cancellingSendCancelsItsWriteAndReleasesCapacity() throws Exception {
        // 发送 future 取消后，旧请求不能继续占用出站槽位。
        try (Fixture fixture = new Fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(44);
            fixture.session.send(outgoing).cancel();
            assertTrue(writing.isCancelled());
            assertTrue(outgoing.completion().isDone());

            // 后续普通调用能使用刚释放的槽位。
            fixture.session.send(request(45)).get(1, TimeUnit.SECONDS);
            assertEquals(2, fixture.network.writes.size());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void outgoingCapacityRejectionDoesNotFailTheExistingCall() throws Exception {
        // 单槽满载只拒绝新请求，保留旧请求的关联。
        try (Fixture fixture = new Fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload first = request(46);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.session.send(request(47)).getCause() instanceof IOException);

            fixture.reply(first, fixture.outgoing(0), "first");
            assertEquals("first", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void duplicateCoreRequestIdDoesNotReplaceTheOriginal() throws Exception {
        // 相同 long 请求 ID 不能登记两个调用。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload first = request(Long.MAX_VALUE);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.session.send(request(Long.MAX_VALUE)).getCause() instanceof IOException);

            fixture.reply(first, fixture.outgoing(0), "kept");
            assertEquals("kept", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void onewayAcceptWaitsUntilTheWholeWriteCompletes() throws Exception {
        // 原生单向方法没有远端回执，内部 Accept 必须等完整写出。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = notification(51);
            Future<Void> sent = fixture.session.send(outgoing);
            assertFalse(sent.isDone());
            assertTrue(fixture.messages.isEmpty());
            assertEquals(TMessageType.ONEWAY, fixture.outgoing(0).messageType());

            // 完成提交只发内部确认，不写一条伪造的 REPLY。
            writing.completed(null);
            sent.get(1, TimeUnit.SECONDS);
            assertEquals(ProtocolStatus.Accept, ((ResponsePayload) fixture.messages.get(0)).getStatus());
            assertEquals(1, fixture.network.writes.size());
            assertTrue(outgoing.completion().isDone());
        }
    }

    @Test
    public void onewayWriteFailureIsNeverAccepted() throws Exception {
        // 写出失败保留原始原因，不能变成提交成功。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = notification(52);
            Future<Void> sent = fixture.session.send(outgoing);
            IOException failure = new IOException("broken write");
            writing.failed(failure);

            assertSame(failure, sent.getCause());
            assertSame(failure, outgoing.completion().getCause());
            assertSame(failure, ((ThrowPayload) fixture.messages.get(0)).getThrowable());
        }
    }

    @Test
    public void ordinaryVoidMethodProducesANativeReply() throws Exception {
        // void 返回类型并不表示 IDL oneway。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload ping = methodRequest(53, "ping");
            fixture.session.receive(fixture.incoming(ping, 93));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(incoming.getRequestID(), null)).get(1, TimeUnit.SECONDS);

            // 普通 void 的空结果仍使用 REPLY 与原序号。
            ThriftMessage reply = fixture.outgoing(0);
            assertEquals(TMessageType.REPLY, reply.messageType());
            assertEquals(93, reply.sequenceId());
            assertFalse(incoming.isMessage());
        }
    }

    @Test
    public void declaredExceptionRetainsItsGeneratedType() throws Exception {
        // 声明异常经 native result 的异常字段恢复，不转成普通状态异常。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = methodRequest(54, "fail");
            outgoing.addParameter(String.class.getName(), "business");
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ResponsePayload response = response(54, null);
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new DeclaredFailure(7, "declared"));
            byte[] reply = fixture.codec.encodeResponse(outgoing, response, fixture.outgoing(0));
            fixture.session.receive(fixture.networkBytes(reply));

            Throwable failure = ((ThrowPayload) fixture.messages.get(0)).getThrowable();
            assertTrue(failure instanceof DeclaredFailure);
            assertEquals("declared", ((DeclaredFailure) failure).getReason());
            assertSame(failure, outgoing.completion().getCause());
        }
    }

    @Test
    public void unknownMethodReturnsAnApplicationErrorAndDoesNotPoisonTheNextCall() throws Exception {
        // 完整但未知的方法应独立失败，连接仍可承载下一次调用。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            byte[] unknown = fixture.wire.encode("binary", "missing", TMessageType.CALL, //
                    101, new InteropService.ping_args());
            fixture.session.receive(fixture.networkBytes(unknown));
            ThriftMessage error = fixture.outgoing(0);
            assertEquals(TMessageType.EXCEPTION, error.messageType());
            assertEquals(101, error.sequenceId());
            assertTrue(fixture.messages.isEmpty());

            fixture.session.receive(fixture.incoming(request(55), 102));
            assertEquals(1, fixture.messages.size());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void unknownOnewayMethodDoesNotProduceAnyResponse() throws Exception {
        // 单向请求即使未知，也不得写出 application exception。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            byte[] unknown = fixture.wire.encode("binary", "missing", TMessageType.ONEWAY, //
                    103, new InteropService.ping_args());
            fixture.session.receive(fixture.networkBytes(unknown));
            assertTrue(fixture.messages.isEmpty());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void duplicateIncomingSequenceDoesNotReplaceTheFirstInvocation() throws Exception {
        // 活跃普通调用的相同 wire seqid 不能覆盖已有入站状态。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.incoming(request(56), 105);
            fixture.session.receive(bytes);
            fixture.session.receive(bytes);
            assertEquals(1, fixture.messages.size());
            assertEquals(TMessageType.EXCEPTION, fixture.outgoing(0).messageType());

            // 原调用仍可正常应答。
            RequestPayload first = (RequestPayload) fixture.messages.get(0);
            fixture.session.send(response(first.getRequestID(), "first")).get(1, TimeUnit.SECONDS);
            assertEquals(TMessageType.REPLY, fixture.outgoing(1).messageType());
        }
    }

    @Test
    public void incomingSequencesAcrossSignedWrapPreserveTheirWireIdentity() throws Exception {
        // 线上 int 序号跨过有符号边界时，两个调用仍拥有独立的本地 long 身份。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.incoming(request(561), Integer.MAX_VALUE));
            fixture.session.receive(fixture.incoming(request(562), Integer.MIN_VALUE));
            RequestPayload first = (RequestPayload) fixture.messages.get(0);
            RequestPayload second = (RequestPayload) fixture.messages.get(1);
            assertNotEquals(first.getRequestID(), second.getRequestID());

            // 反序完成仍恢复各自的原生 int 序号。
            fixture.session.send(response(second.getRequestID(), "second")).get(1, TimeUnit.SECONDS);
            fixture.session.send(response(first.getRequestID(), "first")).get(1, TimeUnit.SECONDS);
            assertEquals(Integer.MIN_VALUE, fixture.outgoing(0).sequenceId());
            assertEquals(Integer.MAX_VALUE, fixture.outgoing(1).sequenceId());
        }
    }

    @Test
    public void reusedIncomingSequenceCannotBeAnsweredByAStaleCoreResponse() throws Exception {
        // 旧入站调用完成后，对端可以复用相同线上序号。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.incoming(request(57), 106);
            fixture.session.receive(bytes);
            RequestPayload first = (RequestPayload) fixture.messages.get(0);
            first.complete(new TimeoutException("first expired"));
            fixture.session.receive(bytes);
            RequestPayload second = (RequestPayload) fixture.messages.get(1);
            assertNotEquals(first.getRequestID(), second.getRequestID());

            // 旧 core ID 的迟到业务响应不能替第二条调用写回。
            Future<Void> stale = fixture.session.send(response(first.getRequestID(), "stale"));
            assertTrue(stale.getCause() instanceof IllegalStateException);
            assertTrue(fixture.network.writes.isEmpty());
            fixture.session.send(response(second.getRequestID(), "second")).get(1, TimeUnit.SECONDS);
            assertEquals(106, fixture.outgoing(0).sequenceId());
        }
    }

    @Test
    public void incomingCapacityReturnsAnErrorWithoutDispatchingAnotherCall() throws Exception {
        // 入站单槽满载只拒绝新调用，不覆盖已有上下文。
        try (Fixture fixture = new Fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            fixture.session.receive(fixture.incoming(request(58), 107));
            fixture.session.receive(fixture.incoming(request(59), 108));
            assertEquals(1, fixture.messages.size());
            assertEquals(108, fixture.outgoing(0).sequenceId());
            assertEquals(TMessageType.EXCEPTION, fixture.outgoing(0).messageType());
        }
    }

    @Test
    public void prepareDrainAnswersUnsentIncomingCallsWithAClosingException() throws Exception {
        // 已分发但尚未应答的普通入站调用在排空前得到明确关闭异常。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.incoming(request(61), 111));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.prepareDrain();
            fixture.session.prepareDrain();

            ThriftMessage error = fixture.outgoing(0);
            assertEquals(TMessageType.EXCEPTION, error.messageType());
            assertEquals(111, error.sequenceId());
            assertTrue(incoming.completion().getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void prepareDrainLeavesAlreadySubmittedWritesToTheTransport() throws Exception {
        // 排空只结束未应答交换，不撤回已经提交的网络写入。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            Future<Void> sent = fixture.session.send(request(62));
            fixture.session.prepareDrain();
            assertFalse(writing.isCancelled());
            assertFalse(sent.isDone());

            // 传输可以完成已有写入，新请求则不能继续提交。
            writing.completed(null);
            sent.get(1, TimeUnit.SECONDS);
            assertTrue(fixture.session.send(request(63)).getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void prepareDrainNeverRepliesToAnIncomingOneway() throws Exception {
        // 未完成的单向入站调用只结束本地生命周期。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.incoming(notification(64), 112));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            fixture.session.prepareDrain();
            assertTrue(incoming.completion().isDone());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void incomingCallAfterDrainGetsAClosingExceptionWithoutDispatch() throws Exception {
        // 排空开始后到达的新普通调用不能再次交给业务层。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.prepareDrain();
            fixture.session.receive(fixture.incoming(request(65), 113));
            assertTrue(fixture.messages.isEmpty());
            assertEquals(TMessageType.EXCEPTION, fixture.outgoing(0).messageType());
            assertEquals(113, fixture.outgoing(0).sequenceId());
        }
    }

    @Test
    public void closedFinishesBothDirectionsOnceAndDoesNotCloseBorrowedResources() throws Exception {
        // 同时存在入站业务和尚未写出的出站请求。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.incoming(request(71), 121));
            RequestPayload incoming = (RequestPayload) fixture.messages.remove(0);
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(72);
            Future<Void> sent = fixture.session.send(outgoing);
            IOException failure = new IOException("peer closed");
            outgoing.completion().onFinal(done -> fixture.session.closed(done.getCause()));
            fixture.session.closed(failure);
            fixture.session.closed(failure);

            // 回调重入不重复通知，网络与执行器仍由外层所有者管理。
            assertSame(failure, sent.getCause());
            assertSame(failure, incoming.completion().getCause());
            assertSame(failure, outgoing.completion().getCause());
            assertTrue(writing.isCancelled());
            assertEquals(1, fixture.messages.size());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void closedSessionIgnoresLateNetworkBytes() throws Exception {
        // 迟到的网络回调不能重新激活已关闭会话。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.closed(null);
            fixture.session.receive(fixture.incoming(request(73), 122));
            assertTrue(fixture.messages.isEmpty());
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.session.send(request(74)).getCause() instanceof IOException);
        }
    }

    @Test
    public void closeAfterRpcCompletionDoesNotDeliverAnotherFailure() throws Exception {
        // RPC 已完成，但清理关联的串行回调尚未执行。
        try (Fixture fixture = new Fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(75);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            fixture.network.deferCallbacks = true;
            TimeoutException timeout = new TimeoutException("already expired");
            outgoing.complete(timeout);
            fixture.session.closed(new IOException("connection closed later"));

            // 关闭只释放残留状态，不重复通知已经结束的 RPC。
            fixture.network.runCallbacks();
            assertSame(timeout, outgoing.completion().getCause());
            assertTrue(fixture.messages.isEmpty());
            assertEquals(0, fixture.network.closes);
        }
    }

    private static RequestPayload request(long id) {
        RequestPayload request = methodRequest(id, "echo");
        request.addParameter(String.class.getName(), "hello");
        return request;
    }

    private static RequestPayload notification(long id) {
        RequestPayload request = methodRequest(id, "notify");
        request.setMessage(true);
        request.addParameter(String.class.getName(), "hello");
        return request;
    }

    private static RequestPayload methodRequest(long id, String method) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceName(Service.class.getName());
        request.setServiceGroup("default");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod(method);
        request.setSerializeType("thrift");
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

    private static final class Fixture implements AutoCloseable {
        private final MetContext      context;
        private final MemoryNetwork   network  = new MemoryNetwork();
        private final List<Payload>   messages = new ArrayList<>();
        private final List<Long>      ids      = new ArrayList<>();
        private final ThriftWireCodec wire     = new ThriftWireCodec(8 * 1024 * 1024, 100000, 64);
        private final boolean         framed;
        private final ThriftCodec     codec;
        private final ThriftSession   session;

        private Fixture(Map<String, String> options) throws Exception {
            // 只借用真实元信息容器，单元测试不启动监听或执行 RPC。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
            configuration.connector("tcp").protocol("thrift");
            this.context = configuration.buildContext();
            this.context.publisher().rsfService(Service.class, new Service() {
                @Override
                public String echo(String value) {
                    return value;
                }

                @Override
                public void ping() {
                }

                @Override
                public String fail(String reason) throws DeclaredFailure {
                    throw new DeclaredFailure(7, reason);
                }

                @Override
                public void notify(String value) {
                }
            }).bindResource("thrift", new ThriftServiceResource(InteropService.class)).register();
            ProtocolConfig config = ProtocolConfig.newConfig("thrift", "thrift", options);
            ProtocolSessionContext protocol = ProtocolSessionContextTestAccess.newContext( //
                    this.context, (action, delay) -> {
                        throw new AssertionError("Thrift must not schedule an RPC timeout or heartbeat");
                    }, null, true, new RouteMountIndex("tcp-0", config));
            this.framed = Boolean.parseBoolean(config.option("framed", "true"));
            this.codec = new ThriftCodec(config, protocol.routes(), this.context);
            this.session = new ThriftSession(config, protocol, this.network, (payload, id) -> {
                this.messages.add(payload);
                this.ids.add(id);
            });
        }

        private byte[] incoming(RequestPayload request, int sequenceId) throws TException {
            return this.networkBytes(this.codec.encodeRequest(request, sequenceId));
        }

        private byte[] networkBytes(byte[] message) {
            if (!this.framed) {
                return message;
            }
            return ByteBuffer.allocate(4 + message.length).putInt(message.length).put(message).array();
        }

        private ThriftMessage outgoing(int index) throws Exception {
            byte[] bytes = this.network.writes.get(index);
            if (this.framed) {
                ByteBuffer framed = ByteBuffer.wrap(bytes);
                int length = framed.getInt();
                byte[] message = new byte[length];
                framed.get(message);
                assertFalse(framed.hasRemaining());
                return this.wire.decode(message);
            }
            return this.wire.decode(bytes);
        }

        private void reply(RequestPayload request, ThriftMessage original, String value) throws Exception {
            byte[] reply = this.codec.encodeResponse(request, response(request.getRequestID(), value), original);
            this.session.receive(this.networkBytes(reply));
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
        private       int               closes;
        private       boolean           deferCallbacks;

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
            return this.closes == 0;
        }

        @Override
        public Future<Void> write(byte[] message) {
            this.writes.add(message);
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
            this.closes++;
            return new BasicFuture<>((Void) null);
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}
