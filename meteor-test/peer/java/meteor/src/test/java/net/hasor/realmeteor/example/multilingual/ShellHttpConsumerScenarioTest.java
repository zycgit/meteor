/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** curl 发送标准文本协议正文，Java 只断言真实响应，不代理 HTTP 调用。 */
@ParameterizedClass(autoCloseArguments = false, name = "curl/{0}/direct", quoteTextArguments = false)
@MethodSource("protocols")
@NxnRpc(name = "soap", version = "1.1", cases = "curl/soap/direct")
@NxnRpc(name = "xmlrpc", cases = "curl/xmlrpc/direct")
@NxnRpc(name = "connect", version = "1", cases = "curl/connect/direct")
public class ShellHttpConsumerScenarioTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:orders";
    private final String protocol;

    public ShellHttpConsumerScenarioTest(String protocol) {
        this.protocol = protocol;
    }
    public static List<Object[]> protocols() {
        // 固定顺序便于矩阵阅读，每个协议生成自己的独立用例。
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { "soap" });
        protocols.add(new Object[] { "xmlrpc" });
        protocols.add(new Object[] { "connect" });
        return protocols;
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void curlSavesAndFindsTheOrder() throws Exception {
        // 提供者只通过公开 bootstrap 入口发布当前协议，使用独立进程和动态端口。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain(this.protocol, "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // curl 的请求与响应保持线上原文，保存成功后再查询相同订单。
            try (OrderPeerProcess save = OrderPeerProcess.start("node", this.command(target, "save"), null)) {
                this.assertOrder(save.awaitOutput());
            }
            try (OrderPeerProcess find = OrderPeerProcess.start("node", this.command(target, "find"), null)) {
                this.assertOrder(find.awaitOutput());
            }
        }
    }

    private List<String> command(String target, String method) {
        String address = target + "/" + this.protocol;
        String contentType = "text/xml; charset=utf-8";
        List<String> command = new ArrayList<>(List.of("curl", "--fail-with-body", "--silent", "--show-error"));
        command.addAll(List.of("--max-time", "15"));
        if (StringUtils.equals("soap", this.protocol)) {
            address += "/orders";
            command.addAll(List.of("--header", "SOAPAction: \"" + NAMESPACE + "/" + method + "\""));
        } else if (StringUtils.equals("connect", this.protocol)) {
            address += "/meteor.example.OrderDemoService/" + method;
            contentType = "application/json";
            command.addAll(List.of("--header", "Connect-Protocol-Version: 1"));
        }
        command.addAll(List.of("--header", "Content-Type: " + contentType));
        command.addAll(List.of("--data-binary", this.body(method), address));
        return command;
    }

    private String body(String method) {
        boolean save = StringUtils.equals("save", method);
        if (StringUtils.equals("connect", this.protocol)) {
            if (save) {
                return "{\"id\":1001,\"description\":\"Order from curl\",\"attachment\":\"TWV0ZW9y\"}";
            }
            return "{\"id\":1001}";
        }
        if (StringUtils.equals("soap", this.protocol)) {
            String fields = "<id>1001</id>";
            if (save) {
                fields += "<description>Order from curl</description><attachment>TWV0ZW9y</attachment>";
            }
            return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                    + "<m:" + method + " xmlns:m=\"" + NAMESPACE + "\"><arg0>" + fields + "</arg0>"
                    + "</m:" + method + "></s:Body></s:Envelope>";
        }

        // XML-RPC 的结构字段采用标准 int、string 与 base64 值。
        String members = "<member><name>id</name><value><int>1001</int></value></member>";
        if (save) {
            members += "<member><name>description</name><value><string>Order from curl</string></value></member>"
                    + "<member><name>attachment</name><value><base64>TWV0ZW9y</base64></value></member>";
        }
        return "<methodCall><methodName>orders." + method + "</methodName><params><param><value><struct>"
                + members + "</struct></value></param></params></methodCall>";
    }

    private void assertOrder(String response) throws Exception {
        if (StringUtils.equals("connect", this.protocol)) {
            var order = new ObjectMapper().readTree(response);
            OrderPeerProcess.assertOrder(order, "Order from curl", "TWV0ZW9y");
            return;
        }

        // 解析服务端原始 XML；故障正文和缺失字段均会让当前场景失败。
        DocumentBuilderFactory factory = DocumentBuilderFactory.newNSInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response)));
        if (StringUtils.equals("soap", this.protocol)) {
            assertEquals(0, document.getElementsByTagNameNS("*", "Fault").getLength(), response);
            assertEquals("1001", document.getElementsByTagNameNS("*", "id").item(0).getTextContent());
            String description = document.getElementsByTagNameNS("*", "description").item(0).getTextContent();
            String attachment = document.getElementsByTagNameNS("*", "attachment").item(0).getTextContent();
            assertEquals("Order from curl", description);
            assertEquals("TWV0ZW9y", attachment);
            return;
        }
        assertEquals(0, document.getElementsByTagName("fault").getLength(), response);
        NodeList members = document.getElementsByTagName("member");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < members.getLength(); index++) {
            Element member = (Element) members.item(index);
            String name = member.getElementsByTagName("name").item(0).getTextContent();
            String value = member.getElementsByTagName("value").item(0).getTextContent();
            fields.put(name, value);
        }
        assertEquals("1001", fields.get("id"));
        assertEquals("Order from curl", fields.get("description"));
        assertEquals("TWV0ZW9y", fields.get("attachment"));
    }
}
