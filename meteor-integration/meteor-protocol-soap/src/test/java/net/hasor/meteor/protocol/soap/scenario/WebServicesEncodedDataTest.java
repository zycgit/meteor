/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.scenario.example.EncodedDataService;
import net.hasor.meteor.protocol.soap.scenario.example.EncodedDataServiceImpl;
import net.hasor.meteor.protocol.soap.scenario.example.EncodedGraphNode;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesEncodedDataTest {
    @Test(timeout = 20000)
    public void rpcSoap11EncodedGraphRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 rpc/encoded SOAP 1.1，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 一个请求同时包含共享节点和指回根的环，返回结果必须保持同一张图。
            EncodedGraphNode root = new EncodedGraphNode();
            root.setValue("root");
            EncodedGraphNode shared = new EncodedGraphNode();
            shared.setValue("shared");
            root.setNext(shared);
            root.setSibling(shared);
            shared.setNext(root);
            EncodedGraphNode result = remote.graph(root);
            assertEquals("root", result.getValue());
            assertEquals("shared", result.getNext().getValue());
            assertSame(result.getNext(), result.getSibling());
            assertSame(result, result.getNext().getNext());
        }
    }

    @Test(timeout = 20000)
    public void rpcSoap12EncodedGraphRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 rpc/encoded SOAP 1.2，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 一个请求同时包含共享节点和指回根的环，返回结果必须保持同一张图。
            EncodedGraphNode root = new EncodedGraphNode();
            root.setValue("root");
            EncodedGraphNode shared = new EncodedGraphNode();
            shared.setValue("shared");
            root.setNext(shared);
            root.setSibling(shared);
            shared.setNext(root);
            EncodedGraphNode result = remote.graph(root);
            assertEquals("root", result.getValue());
            assertEquals("shared", result.getNext().getValue());
            assertSame(result.getNext(), result.getSibling());
            assertSame(result, result.getNext().getNext());
        }
    }

    @Test(timeout = 20000)
    public void documentSoap11EncodedGraphRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 document/encoded SOAP 1.1，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "document").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "document").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 一个请求同时包含共享节点和指回根的环，返回结果必须保持同一张图。
            EncodedGraphNode root = new EncodedGraphNode();
            root.setValue("root");
            EncodedGraphNode shared = new EncodedGraphNode();
            shared.setValue("shared");
            root.setNext(shared);
            root.setSibling(shared);
            shared.setNext(root);
            EncodedGraphNode result = remote.graph(root);
            assertEquals("root", result.getValue());
            assertEquals("shared", result.getNext().getValue());
            assertSame(result.getNext(), result.getSibling());
            assertSame(result, result.getNext().getNext());
        }
    }

    @Test(timeout = 20000)
    public void documentSoap12EncodedGraphRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 document/encoded SOAP 1.2，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "document").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "document").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 一个请求同时包含共享节点和指回根的环，返回结果必须保持同一张图。
            EncodedGraphNode root = new EncodedGraphNode();
            root.setValue("root");
            EncodedGraphNode shared = new EncodedGraphNode();
            shared.setValue("shared");
            root.setNext(shared);
            root.setSibling(shared);
            shared.setNext(root);
            EncodedGraphNode result = remote.graph(root);
            assertEquals("root", result.getValue());
            assertEquals("shared", result.getNext().getValue());
            assertSame(result.getNext(), result.getSibling());
            assertSame(result, result.getNext().getNext());
        }
    }

    @Test(timeout = 20000)
    public void rpcSoap11EncodedMatrixRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 rpc/encoded SOAP 1.1，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 矩形多维数组通过标准 Encoding 的维度信息往返，不转换为文本集合。
            String[][] input = { { "one", "two" }, { "three", "four" } };
            String[][] result = remote.matrix(input);
            assertEquals(2, result.length);
            assertArrayEquals(input[0], result[0]);
            assertArrayEquals(input[1], result[1]);
        }
    }

    @Test(timeout = 20000)
    public void rpcSoap12EncodedMatrixRoundTrip() throws Exception {
        // 两端经 bootstrap 挂载 rpc/encoded SOAP 1.2，仅服务端开启监听。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2") //
                .option("soapStyle", "rpc").option("soapUse", "encoded");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(EncodedDataService.class, new EncodedDataServiceImpl()) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            MetBindInfo<EncodedDataService> service = client.publisher().rsfService(EncodedDataService.class) //
                    .aliasName("soap", "EncodedData").protocol("soap").register();
            EncodedDataService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);

            // 矩形多维数组通过标准 Encoding 的维度信息往返，不转换为文本集合。
            String[][] input = { { "one", "two" }, { "three", "four" } };
            String[][] result = remote.matrix(input);
            assertEquals(2, result.length);
            assertArrayEquals(input[0], result[0]);
            assertArrayEquals(input[1], result[1]);
        }
    }

}
