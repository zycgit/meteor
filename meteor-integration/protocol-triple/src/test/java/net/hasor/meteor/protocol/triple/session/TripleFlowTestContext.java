/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.session;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.triple.codec.TripleHttpCodec;

/** 用真实调用和可逐步执行的工作队列观察 Flow 行为，不模拟协议实现。 */
final class TripleFlowTestContext implements AutoCloseable {
    final         MetContext          context;
    final         TripleHttpCodec     codec;
    final         RequestPayload      request;
    final         TripleStreamingCall call;
    final         HttpExchange        exchange;
    final         List<Payload>       received = new ArrayList<>();
    private final Queue<Runnable>     workers  = new ArrayDeque<>();

    private TripleFlowTestContext(boolean client, Executor executor, int messageLimit, String method) throws Exception {
        // 声明真实服务元信息，使请求和响应按正式 POJO wrapper 编解码链路处理。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").protocol("triple").contextPath("/triple") //
                .option("httpVersion", 2).option("maxMessageSize", messageLimit);
        this.context = configuration.buildContext();
        MetBindInfo<Messages> service = this.context.publisher().rsfService(Messages.class) //
                .aliasName("triple", "example.Messages").serialize("Hessian2").register();
        ConnectorConfig connector = this.context.getSettings().getConnectorConfigs().iterator().next();
        ProtocolConfig config = connector.protocol("triple");
        this.codec = new TripleHttpCodec(config, new RouteMountIndex(connector.name(), config), this.context);
        this.request = new RequestPayload();
        this.request.setRequestID(101);
        this.request.setServiceGroup(service.getBindGroup());
        this.request.setServiceName(service.getBindName());
        this.request.setServiceVersion(service.getBindVersion());
        this.request.setTargetMethod(method);
        if (StringUtils.equals(method, "serverMessages")) {
            this.request.addParameter(String.class.getName(), "first");
            this.request.addParameter(int.class.getName(), 2);
        } else {
            this.request.addParameter(Flow.Publisher.class.getName(), null);
        }
        this.request.setSerializeType("Hessian2");
        this.request.setClientTimeout(5000);

        // 调用独占 HTTP 交换；队列只控制工作时机，保留正式调用的资源释放行为。
        Executor executing = executor == null ? this.workers::add : executor;
        this.call = new TripleStreamingCall(this.request, this.codec, client, executing, //
                Runnable::run, (payload, id) -> this.received.add(payload));
        HttpBodyStream requestBody = this.call.output();
        if (!client) {
            requestBody = new HttpBodyStream(1);
            requestBody.finish(Map.of());
        }
        this.exchange = new HttpExchange(this.codec.streamRequest(this.request, requestBody));
        this.call.attach(this.exchange, client ? null : this.exchange::respond);
    }

    static TripleFlowTestContext newClient() throws Exception {
        return new TripleFlowTestContext(true, null, 4 * 1024 * 1024, "exchange");
    }

    static TripleFlowTestContext newClientWithWorkers(Executor workers) throws Exception {
        return new TripleFlowTestContext(true, workers, 4 * 1024 * 1024, "exchange");
    }

    static TripleFlowTestContext newServer(Executor workers, int messageLimit) throws Exception {
        return new TripleFlowTestContext(false, workers, messageLimit, "exchange");
    }

    static TripleFlowTestContext newClientServerStream() throws Exception {
        return new TripleFlowTestContext(true, null, 4 * 1024 * 1024, "serverMessages");
    }

    static TripleFlowTestContext newClientVoidResponse() throws Exception {
        return new TripleFlowTestContext(true, null, 4 * 1024 * 1024, "consume");
    }

    static TripleFlowTestContext newServerVoidResponse() throws Exception {
        return new TripleFlowTestContext(false, null, 4 * 1024 * 1024, "consume");
    }

    void source(Flow.Publisher<?> source) {
        this.request.getParameterValues().set(0, source);
    }

    byte[] frame(String message) throws Exception {
        byte[] encoded = this.codec.encodeValue(this.request, message, true);
        return this.codec.frame(encoded, "identity");
    }

    @SuppressWarnings("unchecked")
    Flow.Publisher<Object> response(String... values) throws Exception {
        // 完整响应包含消息帧和成功 trailers，消息投递仍由接收流按 demand 驱动。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String value : values) {
            byte[] encoded = this.codec.encodeValue(this.request, value, false);
            body.write(this.codec.frame(encoded, "identity"));
        }
        Map<String, List<String>> headers = this.codec.responseHeaders(this.request, new ResponsePayload());
        Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
        HttpResponse response = HttpResponse.newBuffered(200, headers, body.toByteArray(), trailers);
        this.call.receiveResponse(response);
        this.runTasks();
        ResponsePayload payload = (ResponsePayload) this.received.get(0);
        return (Flow.Publisher<Object>) payload.getReturnData();
    }

    void runTasks() {
        int count = 0;
        while (!this.workers.isEmpty()) {
            if (++count > 1000) {
                throw new IllegalStateException("Flow worker queue did not become idle");
            }
            this.workers.remove().run();
        }
    }

    @Override
    public void close() throws Exception {
        try {
            this.call.cancel();
            this.runTasks();
        } finally {
            this.context.close();
        }
    }

    public interface Messages {
        Flow.Publisher<String> exchange(Flow.Publisher<String> messages);

        Flow.Publisher<String> serverMessages(String prefix, int count);

        void consume(Flow.Publisher<String> messages);
    }
}
