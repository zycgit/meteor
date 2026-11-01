/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.session;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TripleStreamingCallTest {
    @Test
    public void serverStreamingSendsAllOrdinaryParametersInOneRequestFrame() throws Exception {
        // 服务端流允许多个普通参数，调用方将整组参数编码成一个请求帧。
        try (TripleFlowTestContext test = TripleFlowTestContext.newClientServerStream()) {
            test.call.startRequest();
            test.runTasks();
            byte[] body = test.call.output().readAllBytes();
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            byte[] message = test.codec.readMessage(input, "identity");
            assertNotNull(message);
            assertNull(test.codec.readMessage(input, "identity"));

            // 经真实解码器恢复全部参数，不能只保留第一个参数。
            RequestPayload decoded = new RequestPayload();
            decoded.setServiceGroup(test.request.getServiceGroup());
            decoded.setServiceName(test.request.getServiceName());
            decoded.setServiceVersion(test.request.getServiceVersion());
            decoded.setTargetMethod(test.request.getTargetMethod());
            decoded.setSerializeType(test.request.getSerializeType());
            test.codec.decodeRequestValue(decoded, message);
            assertEquals(List.of("first", 2), decoded.getParameterValues());
            assertFalse(test.call.completion().isDone());
        }
    }

    @Test
    public void clientStreamingVoidResultStillConsumesOneResponseFrame() throws Exception {
        // void 结果仍是一个有效响应帧，不能将解码后的 null 与 HTTP EOF 混淆。
        try (TripleFlowTestContext test = TripleFlowTestContext.newClientVoidResponse()) {
            byte[] value = test.codec.encodeValue(test.request, null, false);
            byte[] frame = test.codec.frame(value, "identity");
            Map<String, List<String>> headers = test.codec.responseHeaders(test.request, new ResponsePayload());
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse response = HttpResponse.newBuffered(200, headers, frame, trailers);
            test.call.receiveResponse(response);
            test.runTasks();

            // 收到正常 void 返回后，整次调用成功而不是等待第二条消息。
            assertEquals(1, test.received.size());
            ResponsePayload result = (ResponsePayload) test.received.get(0);
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertNull(result.getReturnData());
            assertTrue(test.call.completion().isDone());
            assertNull(test.call.completion().getCause());
        }
    }

    @Test
    public void serverVoidResultDoesNotSendNullToFlowSubscriber() throws Exception {
        // 客户端流的业务方法返回 void；协议结果不是一条用户 Flow 消息。
        try (TripleFlowTestContext test = TripleFlowTestContext.newServerVoidResponse()) {
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(test.request.getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnType(void.class.getName());
            test.call.respond(response);
            test.runTasks();

            // 响应正常结束并携带成功状态，不能被 Flow 的禁止 null 契约误拒绝。
            HttpResponse encoded = test.exchange.response().getResult();
            assertNotNull(encoded);
            byte[] body = encoded.stream().readAllBytes();
            byte[] message = test.codec.readMessage(new ByteArrayInputStream(body), "identity");
            assertNotNull(message);
            assertEquals(List.of("0"), encoded.stream().trailers().get("grpc-status"));
            assertNull(test.call.completion().getCause());
        }
    }

    @Test
    public void workerRejectionReleasesCallAndNotifiesCaller() throws Exception {
        // 挂载工作池不能接收新任务时，协议必须终止本次调用。
        RejectedExecutionException failure = new RejectedExecutionException("workers full");
        try (TripleFlowTestContext test = TripleFlowTestContext.newClientWithWorkers(task -> {
            throw failure;
        })) {
            assertFalse(test.call.execute(() -> fail("rejected task must not run")));

            // 原始拒绝原因交付 Caller，并释放当前调用拥有的交换。
            assertSame(failure, test.call.completion().getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, test.request.completion().getCause());
            assertEquals(1, test.received.size());
            assertSame(failure, ((ThrowPayload) test.received.get(0)).getThrowable());
        }
    }

    @Test
    public void responseWriteCompletionReleasesServerCall() throws Exception {
        // 业务已经结束，但物理响应仍未写完，调用不能提前释放。
        try (TripleFlowTestContext test = TripleFlowTestContext.newServerVoidResponse()) {
            BasicFuture<Void> writing = new BasicFuture<>();
            test.call.responseWritten(writing);
            assertFalse(test.call.completion().isDone());

            // 所有者通知写完后，协议才完成调用及入站请求。
            writing.completed(null);
            test.runTasks();
            assertTrue(test.call.completion().isDone());
            assertNull(test.call.completion().getCause());
            assertTrue(test.request.completion().isDone());
            assertNull(test.request.completion().getCause());
        }
    }
}
