/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.axis.AxisFault;
import org.apache.axis.Message;
import org.apache.axis.MessageContext;
import org.apache.axis.configuration.XMLStringProvider;
import org.apache.axis.server.AxisServer;

/** 只适配 HTTP 交换，SOAP 解析、RPC 调用和响应编码全部交给真实 AxisServer。 */
public class AxisRpcEncodedPeer implements HttpHandler, AutoCloseable {
    private final AxisServer engine;

    public AxisRpcEncodedPeer() {
        // WSDD 只配置外部 Axis 引擎，明确 operation、参数和返回值的契约名称。
        String deployment = """
                <deployment xmlns="http://xml.apache.org/axis/wsdd/"
                            xmlns:java="http://xml.apache.org/axis/wsdd/providers/java"
                            xmlns:tns="urn:hasor:meteor:soap:RpcEchoService"
                            xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                    <service name="RpcEchoService" provider="java:RPC" style="rpc" use="encoded">
                        <parameter name="className" value="%s"/>
                        <parameter name="allowedMethods" value="echo"/>
                        <parameter name="scope" value="request"/>
                        <operation name="echo" qname="tns:echo" returnQName="return" returnType="xsd:string"
                                   soapAction="urn:hasor:meteor:soap:RpcEchoService/echo">
                            <parameter name="arg0" type="xsd:string" mode="IN"/>
                        </operation>
                    </service>
                </deployment>
                """.formatted(AxisRpcEchoService.class.getName());
        this.engine = new AxisServer(new XMLStringProvider(deployment));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // 只把原始输入流和 HTTP 媒体类型交给 Axis，不在适配层解析 SOAP 正文。
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            Message request = new Message(exchange.getRequestBody(), false, contentType, null);
            MessageContext context = new MessageContext(this.engine);
            context.setTargetService("RpcEchoService");
            context.setRequestMessage(request);

            // Axis 根据请求的 SOAP 版本解码、调用业务方法，并生成结果或标准 Fault。
            int status = 200;
            try {
                this.engine.invoke(context);
            } catch (AxisFault failure) {
                status = 500;
                context.setResponseMessage(new Message(failure));
            }
            Message response = context.getResponseMessage();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.writeTo(output);
            byte[] body = output.toByteArray();

            // Axis 负责选择响应媒体类型和完整 wire，HTTP 适配器只负责发送。
            String responseType = response.getContentType(context.getSOAPConstants());
            exchange.getResponseHeaders().set("Content-Type", responseType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            throw new IOException("Unable to process SOAP request with Axis", failure);
        }
    }

    @Override
    public void close() {
        this.engine.stop();
        this.engine.cleanup();
    }
}
