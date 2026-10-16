/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.connect.codec.ConnectFrameCodec;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;

/** 用真实调用和可逐步执行的工作队列观察 Flow 行为，不模拟协议实现。 */
final class ConnectFlowTestContext implements AutoCloseable {
    final         MetContext           context;
    final         ConnectHttpCodec     codec;
    final         RequestPayload       request;
    final         ConnectStreamingCall call;
    final         HttpExchange         exchange;
    final         List<Payload>        received = new ArrayList<>();
    private final Queue<Runnable>      workers  = new ArrayDeque<>();

    private ConnectFlowTestContext(boolean client, Executor executor, int messageLimit) throws Exception {
        // 声明真实服务元信息，使请求和响应按正式 JSON 编解码链路处理。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").protocol("connect").contextPath("/connect") //
                .option("httpVersion", 2).option("maxMessageSize", messageLimit);
        this.context = configuration.buildContext();
        MetBindInfo<Messages> service = this.context.publisher().rsfService(Messages.class) //
                .aliasName("connect", "example.Messages").serialize("Json").register();
        ProtocolConfig config = this.context.getSettings().getConnectorConfigs().iterator().next().protocol("connect");
        this.codec = new ConnectHttpCodec(config, this.context);
        this.request = new RequestPayload();
        this.request.setRequestID(101);
        this.request.setServiceGroup(service.getBindGroup());
        this.request.setServiceName(service.getBindName());
        this.request.setServiceVersion(service.getBindVersion());
        this.request.setTargetMethod("exchange");
        this.request.setSerializeType("Json");
        this.request.setClientTimeout(5000);

        // 调用独占 HTTP 交换；队列只控制工作时机，保留正式调用的资源释放行为。
        Executor executing = executor == null ? this.workers::add : executor;
        this.call = new ConnectStreamingCall(this.request, this.codec, client, executing, //
                Runnable::run, (payload, id) -> this.received.add(payload));
        HttpBodyStream requestBody = this.call.output();
        if (!client) {
            requestBody = new HttpBodyStream(1);
            requestBody.finish(Map.of());
        }
        this.exchange = new HttpExchange(this.codec.streamRequest(this.request, requestBody));
        this.call.attach(this.exchange, client ? null : this.exchange::respond);
    }

    static ConnectFlowTestContext newClient() throws Exception {
        return new ConnectFlowTestContext(true, null, 4 * 1024 * 1024);
    }

    static ConnectFlowTestContext newServer(Executor workers, int messageLimit) throws Exception {
        return new ConnectFlowTestContext(false, workers, messageLimit);
    }

    void source(Flow.Publisher<?> source) {
        this.request.addParameter(Flow.Publisher.class.getName(), source);
    }

    byte[] frame(String message) throws Exception {
        byte[] encoded = this.codec.encodeValue(this.request, message, true);
        return this.codec.frame(encoded, "identity");
    }

    @SuppressWarnings("unchecked")
    Flow.Publisher<Object> response(String... values) throws Exception {
        // 完整响应包含普通消息和结束信封，消息投递仍由接收流按 demand 驱动。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String value : values) {
            body.write(this.frame(value));
        }
        body.write(new ConnectFrameCodec(4096).end(null, Map.of()));
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/connect+json"));
        HttpResponse response = HttpResponse.newBuffered(200, headers, body.toByteArray(), Map.of());
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
    }
}
