/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.ObjLongConsumer;
import com.caucho.hessian.io.Hessian2Output;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
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
import net.hasor.meteor.protocol.dubbo.codec.DubboCodec;
import net.hasor.meteor.protocol.dubbo.codec.DubboFrame;
import net.hasor.meteor.protocol.dubbo.codec.DubboFrameDecoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 可控串行通道验证会话生命周期；真实网络场景由 realmeteor 包覆盖。 */
public class DubboSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void connectedCompletesReadyWithoutWritingAnApplicationHandshake() throws Exception {
        // 会话建连后直接就绪，重复 connected 不产生额外数据。
        try (Fixture fixture = fixture(Map.of())) {
            assertFalse(fixture.session.ready().isDone());
            fixture.session.connected();
            fixture.session.connected();
            fixture.session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void requestBeforeConnectedIsRejectedWithoutWriting() throws Exception {
        // 网络尚未通知建连，业务调用不得提前提交。
        try (Fixture fixture = fixture(Map.of())) {
            Future<Void> sent = fixture.session.send(request(1));
            assertTrue(sent.getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedPreservesTheOriginalFailure() throws Exception {
        // 传输失败由创建者通知，会话不反向关闭借用网络。
        try (Fixture fixture = fixture(Map.of())) {
            IOException failure = new IOException("connect failed");
            fixture.session.closed(failure);
            fixture.session.connected();
            assertSame(failure, fixture.session.ready().getCause());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void throwPayloadCannotBeEncodedAsAnApplicationMessage() throws Exception {
        // 失败通知属于本地上抛信号，不是 Dubbo 线上业务帧。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            Future<Void> sent = fixture.session.send(new ThrowPayload(new IOException("local")));
            assertTrue(sent.getCause() instanceof UnsupportedOperationException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void fragmentedRequestIsDispatchedOnlyAfterItsFinalByte() throws Exception {
        // 网络逐字节交付一个请求，帧头和正文都可能被切开。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.codec.encode(request(11));
            for (int index = 0; index < bytes.length - 1; index++) {
                fixture.session.receive(new byte[] { bytes[index] });
                assertTrue(fixture.messages.isEmpty());
            }

            // 最后一个字节完成帧并保留原始请求 ID。
            fixture.session.receive(new byte[] { bytes[bytes.length - 1] });
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            assertEquals(11, incoming.getRequestID());
            assertEquals("hello", incoming.getParameterValues().get(0));
        }
    }

    @Test
    public void coalescedRequestsRemainIndependent() throws Exception {
        // 两个请求拼在一个网络块中，解帧不得遗漏第二帧。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(fixture.codec.encode(request(11)));
            bytes.write(fixture.codec.encode(request(12)));
            fixture.session.receive(bytes.toByteArray());

            // 分发顺序与各自 ID 均保持独立。
            assertEquals(2, fixture.messages.size());
            assertEquals(11, ((RequestPayload) fixture.messages.get(0)).getRequestID());
            assertEquals(12, ((RequestPayload) fixture.messages.get(1)).getRequestID());
        }
    }

    @Test
    public void ordinaryRequestWaitsForTheCorrelatedResponse() throws Exception {
        // 完整写出只结束发送 future，不伪造业务返回。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(20);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.messages.isEmpty());
            assertFalse(outgoing.completion().isDone());

            // 回应使用同一 ID 恢复原声明的返回类型。
            fixture.session.receive(fixture.codec.encode(outgoing, response(20, "reply")));
            ResponsePayload actual = (ResponsePayload) fixture.messages.get(0);
            assertEquals("reply", actual.getReturnData());
            assertTrue(outgoing.completion().isDone());
        }
    }

    @Test
    public void businessFailureRetainsItsOriginalExceptionType() throws Exception {
        // 业务异常不能经过内核普通状态响应转换而丢失具体类型。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(21);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            ResponsePayload response = response(21, null);
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new IllegalArgumentException("business fault"));
            fixture.session.receive(fixture.codec.encode(outgoing, response));

            // 通过失败通知交付反序列化的业务错误，并同步结束本地关联。
            Throwable failure = ((ThrowPayload) fixture.messages.get(0)).getThrowable();
            assertTrue(failure instanceof IllegalArgumentException);
            assertEquals("business fault", failure.getMessage());
            assertSame(failure, outgoing.completion().getCause());
        }
    }

    @Test
    public void identicalIdsInBothDirectionsDoNotOverwriteEachOther() throws Exception {
        // 双方同时发起相同 ID 的独立请求。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(25);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            fixture.session.receive(fixture.codec.encode(request(25)));

            // 发送入站响应后，出站请求仍能接收自己的响应。
            fixture.session.send(response(25, "incoming")).get(1, TimeUnit.SECONDS);
            fixture.session.receive(fixture.codec.encode(outgoing, response(25, "outgoing")));
            assertEquals(2, fixture.messages.size());
            assertEquals("outgoing", ((ResponsePayload) fixture.messages.get(1)).getReturnData());
        }
    }

    @Test
    public void lateResponseAfterRpcTimeoutIsDiscarded() throws Exception {
        // 本地 RPC 已超时，协议关联应随 completion 撤销。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(30);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            outgoing.complete(new TimeoutException("RPC expired"));

            // 对端迟到的有效响应不能再次进入 Caller。
            fixture.session.receive(fixture.codec.encode(outgoing, response(30, "late")));
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void rpcCompletionReleasesCapacityBeforeAnyRemoteResponse() throws Exception {
        // 单槽会话先被一个仍在等待业务响应的请求占用。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload first = request(31);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            first.complete(new TimeoutException("expired"));

            // 本地结束后可立刻提交下一请求，不依赖旧响应到达。
            fixture.session.send(request(32)).get(1, TimeUnit.SECONDS);
            assertEquals(2, fixture.network.writes.size());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void outgoingCapacityRejectsAnotherRequestWithoutFailingTheExistingCall() throws Exception {
        // 单槽会话中的第一请求尚未获得结果。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload first = request(36);
            fixture.session.send(first).get(1, TimeUnit.SECONDS);
            Future<Void> rejected = fixture.session.send(request(37));
            assertTrue(rejected.getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());

            // 拒绝新请求不产生能误伤原请求的上层通知。
            fixture.session.receive(fixture.codec.encode(first, response(36, "kept")));
            assertEquals(1, fixture.messages.size());
            assertEquals("kept", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void duplicateOutgoingIdDoesNotReplaceTheOriginalRequest() throws Exception {
        // 重复 ID 在写出前拒绝，保留原有请求关联。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload original = request(33);
            fixture.session.send(original).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.session.send(request(33)).getCause() instanceof IOException);
            assertEquals(1, fixture.network.writes.size());
            assertTrue(fixture.messages.isEmpty());

            // 原请求仍接收自己的业务结果。
            fixture.session.receive(fixture.codec.encode(original, response(33, "original")));
            assertEquals("original", ((ResponsePayload) fixture.messages.get(0)).getReturnData());
        }
    }

    @Test
    public void completedRequestIsRejectedWithoutOccupyingCapacity() throws Exception {
        // 已完成的 RPC 不得被重新发送。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            RequestPayload completed = request(34);
            IOException expired = new IOException("already finished");
            completed.complete(expired);
            assertSame(expired, fixture.session.send(completed).getCause());

            // 拒绝动作不占用请求槽位。
            fixture.session.send(request(35)).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.network.writes.size());
        }
    }

    @Test
    public void networkWriteFailureIsDeliveredAsTheOriginalThrowable() throws Exception {
        // 写入暂不完成，以便在提交后注入原始网络错误。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(40);
            Future<Void> sent = fixture.session.send(outgoing);
            IOException failure = new IOException("broken transport");
            writing.failed(failure);

            // 发送 future 与上抛通知保留同一异常，不映射为 Accept。
            assertSame(failure, sent.getCause());
            assertSame(failure, ((ThrowPayload) fixture.messages.get(0)).getThrowable());
            assertSame(failure, outgoing.completion().getCause());
        }
    }

    @Test
    public void cancellingSendCancelsThePendingWriteAndRpcCorrelation() throws Exception {
        // 已提交的写 future 仍未完成，调用方取消本次提交。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload outgoing = request(41);
            Future<Void> sent = fixture.session.send(outgoing);
            assertTrue(sent.cancel());

            // 只撤销本次等待并释放关联，不关闭借用通道。
            assertTrue(writing.isCancelled());
            assertTrue(outgoing.completion().getCause() instanceof CancellationException);
            fixture.session.send(request(42)).get(1, TimeUnit.SECONDS);
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void onewayAcceptAppearsOnlyAfterTheWholeWriteCompletes() throws Exception {
        // 单向调用提交后，发送完成前不得宣称消息已写出。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload message = request(50);
            message.setMessage(true);
            Future<Void> sent = fixture.session.send(message);
            assertFalse(sent.isDone());
            assertTrue(fixture.messages.isEmpty());

            // 完整写出后仅产生一次内部 Accept，线上帧保持 oneway。
            writing.completed(null);
            ResponsePayload accepted = (ResponsePayload) fixture.messages.get(0);
            assertEquals(ProtocolStatus.Accept, accepted.getStatus());
            assertEquals(50, accepted.getRequestID());
            assertFalse(frame(fixture.network.writes.get(0)).twoWay());
            assertTrue(message.completion().isDone());
        }
    }

    @Test
    public void failedOnewayWriteNeverProducesAnAccept() throws Exception {
        // 单向帧尚未写完时发生网络错误。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            RequestPayload message = request(51);
            message.setMessage(true);
            Future<Void> sent = fixture.session.send(message);
            writing.failed(new IOException("oneway failed"));

            // 只有失败通知，没有虚假的提交成功。
            assertTrue(sent.getCause() instanceof IOException);
            assertEquals(1, fixture.messages.size());
            assertTrue(fixture.messages.get(0) instanceof ThrowPayload);
        }
    }

    @Test
    public void incomingOnewayAcceptDoesNotWriteAnyResponse() throws Exception {
        // 原生单向请求被 Dispatcher 接纳后只结束本地关联。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload message = request(52);
            message.setMessage(true);
            fixture.session.receive(fixture.codec.encode(message));
            RequestPayload incoming = (RequestPayload) fixture.messages.get(0);
            ResponsePayload accepted = response(52, null);
            accepted.setStatus(ProtocolStatus.Accept);
            fixture.session.send(accepted).get(1, TimeUnit.SECONDS);

            // 不向不等回包的对端额外发送确认帧。
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(incoming.completion().isDone());
        }
    }

    @Test
    public void ordinaryIncomingAcceptLeavesTheFinalResponseSlotAvailable() throws Exception {
        // 普通请求的入队通知不是线上临时响应。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.codec.encode(request(53)));
            ResponsePayload accepted = response(53, null);
            accepted.setStatus(ProtocolStatus.Accept);
            fixture.session.send(accepted).get(1, TimeUnit.SECONDS);
            assertTrue(fixture.network.writes.isEmpty());

            // 最终业务结果仍写入同一请求的响应位置。
            fixture.session.send(response(53, "final")).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.network.writes.size());
            assertFalse(frame(fixture.network.writes.get(0)).request());
        }
    }

    @Test
    public void responseWithoutAnIncomingRequestIsRejected() throws Exception {
        // 响应不能凭 ID 在任意连接上发送。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            Future<Void> sent = fixture.session.send(response(60, "orphan"));
            assertTrue(sent.getCause() instanceof IllegalStateException);
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void malformedTwoWayRequestProducesOneBadRequestResponse() throws Exception {
        // 请求正文不是完整调用，仍保留有效 Dubbo 帧头和请求 ID。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0xc2, 61, new byte[] { 'N' }));

            // 错误返回到原请求，不污染业务分发。
            DubboFrame reply = frame(fixture.network.writes.get(0));
            assertEquals(40, reply.status());
            assertEquals(61, reply.requestId());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void malformedOnewayRequestDoesNotProduceAWireResponse() throws Exception {
        // 单向请求即使正文失败，也不能变成带回包的调用。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0x82, 62, new byte[] { 'N' }));
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void malformedResponseFailsOnlyTheCorrelatedRequest() throws Exception {
        // 响应帧本身完整，但业务正文不是合法的返回值分支。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            RequestPayload outgoing = request(66);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            fixture.session.receive(raw((byte) 2, 66, new byte[] { 'N' }));

            // 错误交给当前调用，借用网络仍可承载后续合法调用。
            assertTrue(fixture.messages.get(0) instanceof ThrowPayload);
            assertNotNull(outgoing.completion().getCause());
            fixture.session.send(request(67)).get(1, TimeUnit.SECONDS);
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void duplicateIncomingRequestDoesNotReplaceTheFirstInvocation() throws Exception {
        // 同一连接重复使用尚未完成的入站 ID。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            byte[] bytes = fixture.codec.encode(request(63));
            fixture.session.receive(bytes);
            fixture.session.receive(bytes);
            assertEquals(1, fixture.messages.size());
            assertEquals(40, frame(fixture.network.writes.get(0)).status());

            // 第一条调用仍可正常应答。
            fixture.session.send(response(63, "first")).get(1, TimeUnit.SECONDS);
            assertEquals(2, fixture.network.writes.size());
        }
    }

    @Test
    public void incomingCapacityIsReleasedByRequestCompletion() throws Exception {
        // 入站只允许一个活跃调用，完成后应能接收另一个 ID。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            fixture.session.receive(fixture.codec.encode(request(64)));
            RequestPayload first = (RequestPayload) fixture.messages.get(0);
            first.complete(new TimeoutException("server deadline"));
            fixture.session.receive(fixture.codec.encode(request(65)));
            assertEquals(2, fixture.messages.size());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void exhaustedIncomingCapacityReturnsBadRequestWithoutDispatchingAnotherInvocation() throws Exception {
        // 第一条入站调用占满单槽容量。
        try (Fixture fixture = fixture(Map.of("maxPendingRequests", "1"))) {
            fixture.session.connected();
            fixture.session.receive(fixture.codec.encode(request(68)));
            fixture.session.receive(fixture.codec.encode(request(69)));

            // 后一请求独立失败，未覆盖第一条调用。
            assertEquals(1, fixture.messages.size());
            DubboFrame rejected = frame(fixture.network.writes.get(0));
            assertEquals(69, rejected.requestId());
            assertEquals(40, rejected.status());
            fixture.session.send(response(68, "first")).get(1, TimeUnit.SECONDS);
            assertEquals(2, fixture.network.writes.size());
        }
    }

    @Test
    public void heartbeatRequestProducesAPongWithTheSameId() throws Exception {
        // twoWay 的 null event 是心跳请求。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.codec.heartbeat(-71));
            DubboFrame pong = frame(fixture.network.writes.get(0));
            assertTrue(pong.event());
            assertFalse(pong.request());
            assertEquals(-71, pong.requestId());
            assertNull(fixture.codec.eventValue(pong));
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void onewayHeartbeatDoesNotProduceAPong() throws Exception {
        // null event 只有 twoWay 标志开启时才需要应答。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0xa2, 72, new byte[] { 'N' }));
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void heartbeatResponseDoesNotCreateAReplyLoop() throws Exception {
        // 收到心跳响应只更新读活跃时间，不再次应答。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0x22, 73, new byte[] { 'N' }));
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void readOnlyEventRejectsNewRequestsUntilWritableEventArrives() throws Exception {
        // Dubbo 3 的 W 事件恢复之前由 R 事件禁止的出站提交。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0xa2, 74, hessian("R")));
            assertTrue(fixture.session.send(request(75)).getCause() instanceof IOException);
            assertTrue(fixture.network.writes.isEmpty());

            // 恢复后复用原连接，无需重建会话。
            fixture.session.receive(raw((byte) 0xa2, 76, hessian("W")));
            fixture.session.send(request(77)).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.network.writes.size());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void unknownEventIsIgnoredWithoutAHeartbeatReply() throws Exception {
        // 未知 event 不冒充心跳，也不误入业务分发。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(raw((byte) 0xe2, 78, hessian("future-event")));
            assertTrue(fixture.network.writes.isEmpty());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void disabledHeartbeatDoesNotAllocateATimer() throws Exception {
        // 0 表示完全停用本端主动心跳，仍可被动处理对端事件。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            assertTrue(fixture.timers.isEmpty());
        }
    }

    @Test
    public void idleHeartbeatTimerWritesAProtocolPing() throws Exception {
        // 超过心跳间隔后，以受控调度触发真正的空闲检测。
        Map<String, String> options = Map.of("heartbeatInterval", "5", "heartbeatTimeout", "1000");
        try (Fixture fixture = fixture(options)) {
            fixture.session.connected();
            fireAfter(fixture.timers.get(0), 10);

            // 主动心跳与业务请求分离，并继续预约下一次检测。
            DubboFrame ping = frame(fixture.network.writes.get(0));
            assertTrue(ping.request());
            assertTrue(ping.twoWay());
            assertTrue(ping.event());
            assertNull(fixture.codec.eventValue(ping));
            assertEquals(2, fixture.timers.size());
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test
    public void heartbeatReadTimeoutFailsPendingCallsAndClosesTheTransport() throws Exception {
        // 对端在整个心跳期限内没有交付任何字节。
        Map<String, String> options = Map.of("heartbeatInterval", "5", "heartbeatTimeout", "25");
        try (Fixture fixture = fixture(options)) {
            fixture.session.connected();
            RequestPayload outgoing = request(79);
            fixture.session.send(outgoing).get(1, TimeUnit.SECONDS);
            fireAfter(fixture.timers.get(0), 35);

            // 心跳期限属于连接级故障，上抛未完成请求并请求关闭网络。
            assertEquals(1, fixture.network.closes);
            Throwable failure = ((ThrowPayload) fixture.messages.get(0)).getThrowable();
            assertTrue(failure instanceof TimeoutException);
            assertSame(failure, outgoing.completion().getCause());
            assertTrue(fixture.timers.get(0).cancelled);
        }
    }

    @Test
    public void heartbeatWriteFailureClosesTheBrokenConnection() throws Exception {
        // 被动应答心跳时的写入错误不能成为业务 Accept。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            BasicFuture<Void> writing = new BasicFuture<>();
            fixture.network.nextWrite = writing;
            fixture.session.receive(fixture.codec.heartbeat(-90));
            writing.failed(new IOException("heartbeat write failed"));

            // 协议错误只请求关闭传输，不再分发业务通知。
            assertEquals(1, fixture.network.closes);
            assertTrue(fixture.messages.isEmpty());
            assertTrue(fixture.session.send(request(91)).getCause() instanceof IOException);
        }
    }

    @Test
    public void prepareDrainCancelsTheTimerAndQueuedChecksCannotRestartIt() throws Exception {
        // 已排队的 timer 回调可能在排空开始后才进入串行任务队列。
        Map<String, String> options = Map.of("heartbeatInterval", "10", "heartbeatTimeout", "100");
        try (Fixture fixture = fixture(options)) {
            fixture.session.connected();
            TimerTask timer = fixture.timers.get(0);
            fixture.session.prepareDrain();
            timer.task.run();

            // 取消后不再产生新的心跳写入或新的定时句柄。
            assertTrue(timer.cancelled);
            assertEquals(1, fixture.timers.size());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    @Test
    public void closedCompletesBothDirectionsOnceWithoutClosingTheBorrowedNetwork() throws Exception {
        // 同时存在一个入站调用和一个尚未完成写出的出站调用。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.receive(fixture.codec.encode(request(80)));
            RequestPayload incoming = (RequestPayload) fixture.messages.remove(0);
            fixture.network.nextWrite = new BasicFuture<>();
            RequestPayload outgoing = request(81);
            Future<Void> sent = fixture.session.send(outgoing);
            IOException failure = new IOException("peer disconnected");
            outgoing.completion().onFinal(done -> fixture.session.closed(done.getCause()));
            fixture.session.closed(failure);
            fixture.session.closed(failure);

            // 重入不重复通知，资源所有者仍是外层连接器。
            assertSame(failure, sent.getCause());
            assertSame(failure, incoming.completion().getCause());
            assertSame(failure, outgoing.completion().getCause());
            assertEquals(1, fixture.messages.size());
            assertEquals(0, fixture.network.closes);
        }
    }

    @Test
    public void closedSessionIgnoresIncomingBytesAndRejectsNewWrites() throws Exception {
        // 会话终结后，迟到网络回调不能重新分发或写出。
        try (Fixture fixture = fixture(Map.of())) {
            fixture.session.connected();
            fixture.session.closed(null);
            fixture.session.receive(fixture.codec.encode(request(82)));
            assertTrue(fixture.session.send(request(83)).getCause() instanceof IOException);
            assertTrue(fixture.messages.isEmpty());
            assertTrue(fixture.network.writes.isEmpty());
        }
    }

    private static Fixture fixture(Map<String, String> options) throws Exception {
        return new Fixture(options);
    }

    private static RequestPayload request(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceName(Echo.class.getName());
        request.setServiceGroup("default");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.setSerializeType("Hessian2");
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

    private static DubboFrame frame(byte[] bytes) throws IOException {
        return new DubboFrameDecoder(1024 * 1024).readFrame(ByteBuffer.wrap(bytes));
    }

    private static byte[] hessian(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeObject(value);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] raw(byte flags, long id, byte[] body) {
        ByteBuffer bytes = ByteBuffer.allocate(16 + body.length);
        bytes.putShort((short) 0xdabb).put(flags).put((byte) 20).putLong(id).putInt(body.length).put(body);
        return bytes.array();
    }

    private static void fireAfter(TimerTask timer, long millis) throws Exception {
        // 定时到期是本场景的输入；使用有界 Future 等待，不用休眠轮询结果。
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        try {
            clock.schedule(timer.task, millis, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS);
        } finally {
            clock.shutdownNow();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MetContext      context;
        private final MemoryNetwork   network  = new MemoryNetwork();
        private final List<Payload>   messages = new ArrayList<>();
        private final List<TimerTask> timers   = new ArrayList<>();
        private final DubboCodec      codec;
        private final DubboSession    session;

        private Fixture(Map<String, String> options) throws Exception {
            // 会话单测借用真实元信息容器，不建立监听和真实网络线程。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
            configuration.connector("tcp").protocol("dubbo");
            this.context = configuration.buildContext();
            this.context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("dubbo", Echo.class.getName()) //
                    .serialize("Hessian2").register();
            Map<String, String> configured = new HashMap<>(options);
            configured.putIfAbsent("heartbeatInterval", "0");
            ProtocolConfig config = ProtocolConfig.newConfig("dubbo", "dubbo", configured);
            ProtocolSessionContext protocol = ProtocolSessionContextTestAccess.newContext( //
                    this.context, this::schedule, null, true, new RouteMountIndex("tcp-0", config));
            this.codec = new DubboCodec(config, protocol.routes(), this.context);
            ObjLongConsumer<Payload> receiver = (message, id) -> this.messages.add(message);
            this.session = new DubboSession(config, protocol, this.network, receiver);
        }

        private Cancellable schedule(Runnable action, long delay) {
            TimerTask task = new TimerTask(action);
            this.timers.add(task);
            return task;
        }

        @Override
        public void close() throws Exception {
            this.session.closed(null);
            this.context.close();
        }
    }

    private static final class TimerTask implements Cancellable {
        private final Runnable task;
        private       boolean  cancelled;

        private TimerTask(Runnable task) {
            this.task = task;
        }

        @Override
        public boolean cancel() {
            boolean changed = !this.cancelled;
            this.cancelled = true;
            return changed;
        }
    }

    private static final class MemoryNetwork implements NetworkChannel<byte[]> {
        private final List<byte[]>      writes = new ArrayList<>();
        private       BasicFuture<Void> nextWrite;
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
            task.run();
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