/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.thrift;
import java.net.InetSocketAddress;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.thrift.sdk.generated.OrderThriftService;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

/** 使用 Apache Thrift SDK 发布订单服务，供 Meteor 消费者调用。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 官方 Processor 将请求交给普通 Java 订单实现。
        OrderThriftSdkService implementation = new OrderThriftSdkService(new OrderDemoServiceImpl());
        TProcessor processor = new OrderThriftService.Processor<>(implementation);

        // 使用 Binary 编码和 Framed 传输在本地发布服务。
        try (TServerSocket listener = new TServerSocket(new InetSocketAddress("127.0.0.1", 8080))) {
            TThreadPoolServer.Args options = new TThreadPoolServer.Args(listener) //
                    .processor(processor) //
                    .protocolFactory(new TBinaryProtocol.Factory()) //
                    .transportFactory(new TFramedTransport.Factory());
            TThreadPoolServer server = new TThreadPoolServer(options);
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "thrift-sdk-provider-shutdown"));

            // 在当前线程接受连接，Ctrl+C 关闭服务。
            System.out.println("SDK order provider is ready: tcp://127.0.0.1:8080");
            server.serve();
        }
    }
}
