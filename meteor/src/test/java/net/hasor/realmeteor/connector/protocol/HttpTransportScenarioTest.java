/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.connector.protocol;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.example.connector.protocol.HttpEchoService;
import net.hasor.example.connector.protocol.HttpWirePeer;
import org.junit.Test;
import static org.junit.Assert.*;

/** 从正式启动和发布入口验证 HTTP 分块传输与外部对端互通。 */
public class HttpTransportScenarioTest {
    @Test(timeout = 10000)
    public void fragmentedChunkedRequestInvokesPublishedServiceAndReusesConnection() throws Exception {
        // HTTP 监听器由公开配置构建，文本测试协议负责映射普通回显业务。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("textprotocol");
        try (MetContext server = configuration.buildContext()) {
            server.publisher().rsfService(HttpEchoService.class, value -> value) //
                    .group("http").name("http.echo").version("1").serialize("Java").register();
            server.start();

            // 外部对端将分块请求拆成三字节片段，必须实际经过网络增量解码。
            int port = server.bindAddress("textprotocol").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                String headers = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n";
                String chunks = "3\r\nhel\r\n2\r\nlo\r\n0\r\nX-Trailer: yes\r\n\r\n";
                byte[] request = (headers + chunks).getBytes(StandardCharsets.US_ASCII);
                for (int offset = 0; offset < request.length; offset += 3) {
                    int fragmentLength = Math.min(3, request.length - offset);
                    socket.getOutputStream().write(request, offset, fragmentLength);
                }
                HttpWirePeer.Message response = HttpWirePeer.readMessage(socket.getInputStream());
                assertTrue(response.firstLine().startsWith("HTTP/1.1 200"));
                assertEquals("hello", response.body());

                // 带尾部字段的分块请求完成后，同一连接仍能处理下一次业务调用。
                socket.getOutputStream().write(HttpWirePeer.request("next"));
                HttpWirePeer.Message next = HttpWirePeer.readMessage(socket.getInputStream());
                assertTrue(next.firstLine().startsWith("HTTP/1.1 200"));
                assertEquals("next", next.body());
            }
        }
    }

    @Test(timeout = 10000)
    public void remoteInvocationDecodesChunkedResponseFromExternalServer() throws Exception {
        // 客户端通过正式入口注册服务引用，对端使用独立原始 HTTP 服务器。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("textprotocol");
        try (ServerSocket listener = new ServerSocket(0); //
             MetContext client = configuration.buildContext()) {
            listener.setSoTimeout(3000);
            MetBindInfo<HttpEchoService> service = client.publisher().rsfService(HttpEchoService.class) //
                    .group("http").name("http.echo").version("1").serialize("Java").register();
            client.start();
            InterAddress target = new InterAddress("textprotocol", "127.0.0.1", listener.getLocalPort(), "default");

            // 异步业务调用经真实网络发出，外部服务器确认方法和正文。
            MetClient remote = client.getRsfClient(target);
            Class<?>[] parameterTypes = { String.class };
            Object[] parameterValues = { "request" };
            MetFuture result = remote.asyncInvoke(service, "echo", parameterTypes, parameterValues);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                HttpWirePeer.Message request = HttpWirePeer.readMessage(peer.getInputStream());
                assertTrue(request.firstLine().startsWith("POST "));
                assertEquals("request", request.body());

                // 逐字节写出分块响应，业务调用方应得到合并后的完整结果。
                String headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
                String chunks = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
                byte[] response = (headers + chunks).getBytes(StandardCharsets.US_ASCII);
                for (byte value : response) {
                    peer.getOutputStream().write(value);
                }
                peer.getOutputStream().flush();
                assertEquals("hello world", result.getData(3, TimeUnit.SECONDS));
            }
        }
    }
}
