/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.ContentType;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.soap.MTOMFeature;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.scenario.example.AttachmentHttpProxy;
import net.hasor.meteor.protocol.soap.scenario.example.MetroBinaryService;
import net.hasor.meteor.protocol.soap.scenario.example.MetroBinaryServiceImpl;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesMtomInteropTest {
    private static final String XOP_NAMESPACE = "http://www.w3.org/2004/08/xop/include";

    @Test(timeout = 20000)
    public void meteorClientExchangesMtomWithMetroSoap11Endpoint() throws Exception {
        // 真实 Metro Endpoint 启用 SOAP 1.1 MTOM，所有非空二进制都允许优化为附件。
        MTOMFeature mtom = new MTOMFeature(true, 0);
        Endpoint endpoint = Endpoint.create(SOAPBinding.SOAP11HTTP_BINDING, new MetroBinaryServiceImpl(), mtom);
        HttpServer metroServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            endpoint.publish(metroServer.createContext("/services/BinaryService"));
            metroServer.start();

            // 独立 HTTP 代理记录两端真实报文，不参与 SOAP 或 MIME 编解码。
            int metroPort = metroServer.getAddress().getPort();
            URI target = URI.create("http://127.0.0.1:" + metroPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxyServer.createContext("/services/BinaryService", observed);
                proxyServer.start();

                // Meteor 从 Java 接口生成调用契约，显式开启 MTOM 和零字节阈值。
                MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
                configuration.connector("http") //
                        .protocol("soap") //
                        .contextPath("/services") //
                        .option("soapVersion", "1.1") //
                        .option("attachmentMode", "mtom") //
                        .option("attachmentThreshold", 0);
                try (MetContext context = configuration.buildContext()) {
                    context.start();
                    MetBindInfo<MetroBinaryService> subscribed = context.publisher() //
                            .rsfService(MetroBinaryService.class) //
                            .aliasName("soap", "BinaryService") //
                            .protocol("soap") //
                            .register();

                    // 公开客户端经代理调用 Metro，完整二进制内容必须往返保持一致。
                    int proxyPort = proxyServer.getAddress().getPort();
                    InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                    MetroBinaryService remote = context.getRsfClient(address).getRemote(subscribed);
                    byte[] payload = this.binaryPayload();
                    byte[] result = remote.echo(payload);
                    assertArrayEquals(payload, result);

                    // 双向媒体类型和 XOP 引用证明请求、响应都实际使用 MTOM 附件。
                    ContentType requestType = new ContentType(observed.getRequestContentType());
                    assertTrue(StringUtils.equalsIgnoreCase("multipart/related", requestType.getBaseType()));
                    assertTrue(StringUtils.equalsIgnoreCase("application/xop+xml", requestType.getParameter("type")));
                    ContentType responseType = new ContentType(observed.getResponseContentType());
                    assertTrue(StringUtils.equalsIgnoreCase("multipart/related", responseType.getBaseType()));
                    assertTrue(StringUtils.equalsIgnoreCase("application/xop+xml", responseType.getParameter("type")));
                    String requestWire = new String(observed.getRequestBody(), StandardCharsets.ISO_8859_1);
                    String responseWire = new String(observed.getResponseBody(), StandardCharsets.ISO_8859_1);
                    assertTrue("Meteor request must contain XOP", requestWire.contains(XOP_NAMESPACE));
                    assertTrue("Metro response must contain XOP", responseWire.contains(XOP_NAMESPACE));
                }
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            endpoint.stop();
            metroServer.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void meteorClientExchangesMtomWithMetroSoap12Endpoint() throws Exception {
        // 真实 Metro Endpoint 启用 SOAP 1.2 MTOM，所有非空二进制都允许优化为附件。
        MTOMFeature mtom = new MTOMFeature(true, 0);
        Endpoint endpoint = Endpoint.create(SOAPBinding.SOAP12HTTP_BINDING, new MetroBinaryServiceImpl(), mtom);
        HttpServer metroServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            endpoint.publish(metroServer.createContext("/services/BinaryService"));
            metroServer.start();

            // 独立 HTTP 代理记录两端真实报文，不参与 SOAP 或 MIME 编解码。
            int metroPort = metroServer.getAddress().getPort();
            URI target = URI.create("http://127.0.0.1:" + metroPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxyServer.createContext("/services/BinaryService", observed);
                proxyServer.start();

                // Meteor 从 Java 接口生成调用契约，显式开启 MTOM 和零字节阈值。
                MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
                configuration.connector("http") //
                        .protocol("soap") //
                        .contextPath("/services") //
                        .option("soapVersion", "1.2") //
                        .option("attachmentMode", "mtom") //
                        .option("attachmentThreshold", 0);
                try (MetContext context = configuration.buildContext()) {
                    context.start();
                    MetBindInfo<MetroBinaryService> subscribed = context.publisher() //
                            .rsfService(MetroBinaryService.class) //
                            .aliasName("soap", "BinaryService") //
                            .protocol("soap") //
                            .register();

                    // 公开客户端经代理调用 Metro，完整二进制内容必须往返保持一致。
                    int proxyPort = proxyServer.getAddress().getPort();
                    InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                    MetroBinaryService remote = context.getRsfClient(address).getRemote(subscribed);
                    byte[] payload = this.binaryPayload();
                    byte[] result = remote.echo(payload);
                    assertArrayEquals(payload, result);

                    // 双向媒体类型和 XOP 引用证明请求、响应都实际使用 MTOM 附件。
                    ContentType requestType = new ContentType(observed.getRequestContentType());
                    assertTrue(StringUtils.equalsIgnoreCase("multipart/related", requestType.getBaseType()));
                    assertTrue(StringUtils.equalsIgnoreCase("application/xop+xml", requestType.getParameter("type")));
                    ContentType responseType = new ContentType(observed.getResponseContentType());
                    assertTrue(StringUtils.equalsIgnoreCase("multipart/related", responseType.getBaseType()));
                    assertTrue(StringUtils.equalsIgnoreCase("application/xop+xml", responseType.getParameter("type")));
                    String requestWire = new String(observed.getRequestBody(), StandardCharsets.ISO_8859_1);
                    String responseWire = new String(observed.getResponseBody(), StandardCharsets.ISO_8859_1);
                    assertTrue("Meteor request must contain XOP", requestWire.contains(XOP_NAMESPACE));
                    assertTrue("Metro response must contain XOP", responseWire.contains(XOP_NAMESPACE));
                }
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            endpoint.stop();
            metroServer.stop(0);
        }
    }

    private byte[] binaryPayload() {
        byte[] payload = new byte[4096];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        return payload;
    }
}
