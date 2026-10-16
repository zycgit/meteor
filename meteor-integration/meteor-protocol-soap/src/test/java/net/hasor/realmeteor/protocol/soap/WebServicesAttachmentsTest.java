/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.BinaryPayload;
import net.hasor.example.protocol.soap.BinaryService;
import net.hasor.example.protocol.soap.BinaryServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.AttachmentHttpProxy;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class WebServicesAttachmentsTest {
    private static final String XOP_NAMESPACE = "http://www.w3.org/2004/08/xop/include";

    @Test(timeout = 20000)
    public void soap11MtomDirectBytesRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.1 MTOM，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "mtom");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "mtom");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // 直接 byte[] 参数与返回值经过真实 HTTP 调用后保留全部二进制字节。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                byte[] result = remote.echo(expected);
                assertArrayEquals(expected, result);

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/xop+xml", requestType.getParameter("type"));
                assertEquals("application/xop+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                assertEquals(1, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(1, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap11MtomBinaryDtoRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.1 MTOM，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "mtom");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "mtom");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // DTO 同时包含真正的 byte[] 和外形相同的普通 base64 字符串。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                BinaryPayload payload = new BinaryPayload();
                payload.setData(expected);
                payload.setEncodedText("AAECgP8=");
                BinaryPayload result = remote.echoPayload(payload);
                assertArrayEquals(expected, result.getData());
                assertEquals("AAECgP8=", result.getEncodedText());

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/xop+xml", requestType.getParameter("type"));
                assertEquals("application/xop+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                assertEquals(1, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(1, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());

                // 字符串仍留在 XML 正文内，不会因为内容可解码为 base64 就被错误替换成附件。
                Element requestText = this.element(requestXml, "encodedText");
                Element responseText = this.element(responseXml, "encodedText");
                assertEquals("AAECgP8=", requestText.getTextContent());
                assertEquals("AAECgP8=", responseText.getTextContent());
                assertFalse(requestText.hasAttribute("href"));
                assertFalse(responseText.hasAttribute("href"));
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap12MtomDirectBytesRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.2 MTOM，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "mtom");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "mtom");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // 直接 byte[] 参数与返回值经过真实 HTTP 调用后保留全部二进制字节。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                byte[] result = remote.echo(expected);
                assertArrayEquals(expected, result);

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/xop+xml", requestType.getParameter("type"));
                assertEquals("application/xop+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://www.w3.org/2003/05/soap-envelope";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                assertEquals(1, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(1, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap12MtomBinaryDtoRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.2 MTOM，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "mtom");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "mtom");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // DTO 同时包含真正的 byte[] 和外形相同的普通 base64 字符串。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                BinaryPayload payload = new BinaryPayload();
                payload.setData(expected);
                payload.setEncodedText("AAECgP8=");
                BinaryPayload result = remote.echoPayload(payload);
                assertArrayEquals(expected, result.getData());
                assertEquals("AAECgP8=", result.getEncodedText());

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/xop+xml", requestType.getParameter("type"));
                assertEquals("application/xop+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://www.w3.org/2003/05/soap-envelope";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                assertEquals(1, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(1, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());

                // 字符串仍留在 XML 正文内，不会因为内容可解码为 base64 就被错误替换成附件。
                Element requestText = this.element(requestXml, "encodedText");
                Element responseText = this.element(responseXml, "encodedText");
                assertEquals("AAECgP8=", requestText.getTextContent());
                assertEquals("AAECgP8=", responseText.getTextContent());
                assertFalse(requestText.hasAttribute("href"));
                assertFalse(responseText.hasAttribute("href"));
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap11SwaDirectBytesRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.1 SWA，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "swa");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "swa");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // 直接 byte[] 参数与返回值经过真实 HTTP 调用后保留全部二进制字节。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                byte[] result = remote.echo(expected);
                assertArrayEquals(expected, result);

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("text/xml", requestType.getParameter("type"));
                assertEquals("text/xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                Element requestBinary = this.element(requestXml, "arg0");
                Element responseBinary = this.element(responseXml, "return");
                assertTrue(requestBinary.getAttribute("href").startsWith("cid:"));
                assertTrue(responseBinary.getAttribute("href").startsWith("cid:"));
                assertEquals(0, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(0, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap11SwaBinaryDtoRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.1 SWA，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "swa");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "swa");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // DTO 同时包含真正的 byte[] 和外形相同的普通 base64 字符串。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                BinaryPayload payload = new BinaryPayload();
                payload.setData(expected);
                payload.setEncodedText("AAECgP8=");
                BinaryPayload result = remote.echoPayload(payload);
                assertArrayEquals(expected, result.getData());
                assertEquals("AAECgP8=", result.getEncodedText());

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("text/xml", requestType.getParameter("type"));
                assertEquals("text/xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                Element requestBinary = this.element(requestXml, "data");
                Element responseBinary = this.element(responseXml, "data");
                assertTrue(requestBinary.getAttribute("href").startsWith("cid:"));
                assertTrue(responseBinary.getAttribute("href").startsWith("cid:"));
                assertEquals(0, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(0, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());

                // 字符串仍留在 XML 正文内，不会因为内容可解码为 base64 就被错误替换成附件。
                Element requestText = this.element(requestXml, "encodedText");
                Element responseText = this.element(responseXml, "encodedText");
                assertEquals("AAECgP8=", requestText.getTextContent());
                assertEquals("AAECgP8=", responseText.getTextContent());
                assertFalse(requestText.hasAttribute("href"));
                assertFalse(responseText.hasAttribute("href"));
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap12SwaDirectBytesRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.2 SWA，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "swa");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "swa");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // 直接 byte[] 参数与返回值经过真实 HTTP 调用后保留全部二进制字节。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                byte[] result = remote.echo(expected);
                assertArrayEquals(expected, result);

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/soap+xml", requestType.getParameter("type"));
                assertEquals("application/soap+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://www.w3.org/2003/05/soap-envelope";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                Element requestBinary = this.element(requestXml, "arg0");
                Element responseBinary = this.element(responseXml, "return");
                assertTrue(requestBinary.getAttribute("href").startsWith("cid:"));
                assertTrue(responseBinary.getAttribute("href").startsWith("cid:"));
                assertEquals(0, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(0, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void soap12SwaBinaryDtoRoundTripUsesMultipart() throws Exception {
        // 两端选择 SOAP 1.2 SWA，阈值为零，短二进制也必须使用附件。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "swa");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "swa");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();

            // 代理只转发原始 HTTP 字节，让测试观察请求和响应的实际 MIME 类型。
            int serverPort = server.bindAddress("soap").getPort();
            URI target = URI.create("http://127.0.0.1:" + serverPort + "/services/BinaryService");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(target);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/BinaryService", observed);
                proxy.start();
                MetBindInfo<BinaryService> subscription = client.publisher() //
                        .rsfService(BinaryService.class) //
                        .aliasName("soap", "BinaryService") //
                        .protocol("soap") //
                        .register();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                BinaryService remote = client.getRsfClient(address).getRemote(subscription);

                // DTO 同时包含真正的 byte[] 和外形相同的普通 base64 字符串。
                byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
                BinaryPayload payload = new BinaryPayload();
                payload.setData(expected);
                payload.setEncodedText("AAECgP8=");
                BinaryPayload result = remote.echoPayload(payload);
                assertArrayEquals(expected, result.getData());
                assertEquals("AAECgP8=", result.getEncodedText());

                // 两个方向都必须发送 multipart/related，且每包仅有 SOAP 根和一个二进制附件。
                String requestMediaType = observed.getRequestContentType();
                String responseMediaType = observed.getResponseContentType();
                ContentType requestType = new ContentType(requestMediaType);
                ContentType responseType = new ContentType(responseMediaType);
                assertEquals("multipart/related", requestType.getBaseType());
                assertEquals("multipart/related", responseType.getBaseType());
                assertEquals("application/soap+xml", requestType.getParameter("type"));
                assertEquals("application/soap+xml", responseType.getParameter("type"));
                MimeMultipart requestParts = this.multipart(requestMediaType, observed.getRequestBody());
                MimeMultipart responseParts = this.multipart(responseMediaType, observed.getResponseBody());
                assertEquals(2, requestParts.getCount());
                assertEquals(2, responseParts.getCount());
                Document requestXml = this.rootXml(requestParts, requestType);
                Document responseXml = this.rootXml(responseParts, responseType);
                String envelopeNamespace = "http://www.w3.org/2003/05/soap-envelope";
                assertEquals(envelopeNamespace, requestXml.getDocumentElement().getNamespaceURI());
                assertEquals(envelopeNamespace, responseXml.getDocumentElement().getNamespaceURI());
                Element requestBinary = this.element(requestXml, "data");
                Element responseBinary = this.element(responseXml, "data");
                assertTrue(requestBinary.getAttribute("href").startsWith("cid:"));
                assertTrue(responseBinary.getAttribute("href").startsWith("cid:"));
                assertEquals(0, requestXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());
                assertEquals(0, responseXml.getElementsByTagNameNS(XOP_NAMESPACE, "Include").getLength());

                // 字符串仍留在 XML 正文内，不会因为内容可解码为 base64 就被错误替换成附件。
                Element requestText = this.element(requestXml, "encodedText");
                Element responseText = this.element(responseXml, "encodedText");
                assertEquals("AAECgP8=", requestText.getTextContent());
                assertEquals("AAECgP8=", responseText.getTextContent());
                assertFalse(requestText.hasAttribute("href"));
                assertFalse(responseText.hasAttribute("href"));
            } finally {
                proxy.stop(0);
            }
        }
    }

    private MetConfiguration incomingConfiguration(String version, String attachmentMode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("attachmentMode", attachmentMode) //
                .option("attachmentThreshold", 0);
        return configuration;
    }

    private MetConfiguration outgoingConfiguration(String version, String attachmentMode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("attachmentMode", attachmentMode) //
                .option("attachmentThreshold", 0);
        return configuration;
    }

    private MimeMultipart multipart(String contentType, byte[] body) throws Exception {
        return new MimeMultipart(new ByteArrayDataSource(body, contentType));
    }

    private Document rootXml(MimeMultipart multipart, ContentType packageType) throws Exception {
        // 按 MIME start 定位 SOAP 根，解析器只检查测试观测到的文档，不恢复附件或业务值。
        String start = packageType.getParameter("start");
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart part = multipart.getBodyPart(index);
            String[] ids = part.getHeader("Content-ID");
            boolean namedRoot = ids != null && ids.length == 1 && StringUtils.equals(start, ids[0]);
            boolean firstRoot = start == null && index == 0;
            if (namedRoot || firstRoot) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                try (InputStream input = part.getInputStream()) {
                    return factory.newDocumentBuilder().parse(input);
                }
            }
        }
        throw new IllegalStateException("SOAP MIME package has no root part");
    }

    private Element element(Document document, String localName) {
        return (Element) document.getElementsByTagNameNS("", localName).item(0);
    }
}
