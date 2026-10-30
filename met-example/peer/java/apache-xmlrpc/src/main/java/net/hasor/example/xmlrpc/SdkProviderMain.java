/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.xmlrpc;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import org.apache.xmlrpc.server.PropertyHandlerMapping;
import org.apache.xmlrpc.server.XmlRpcServerConfigImpl;
import org.apache.xmlrpc.webserver.WebServer;

/** 使用 Apache XML-RPC 发布订单服务，供 Meteor 消费者调用。 */
public final class SdkProviderMain {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderDemoServiceImpl orders = new OrderDemoServiceImpl();

    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // Apache SDK 负责 HTTP 服务、方法分派和 XML-RPC 编解码。
        SdkProviderMain service = new SdkProviderMain();
        PropertyHandlerMapping mapping = new PropertyHandlerMapping();
        mapping.setRequestProcessorFactoryFactory(type -> request -> service);
        mapping.addHandler("orders", SdkProviderMain.class);
        XmlRpcServerConfigImpl config = new XmlRpcServerConfigImpl();
        config.setEnabledForExtensions(false);
        WebServer server = new WebServer(8080, InetAddress.getByName("127.0.0.1"));
        try {
            server.getXmlRpcServer().setConfig(config);
            server.getXmlRpcServer().setHandlerMapping(mapping);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "xmlrpc-sdk-shutdown"));
            server.start();
            System.out.println("SDK order provider is ready: http://127.0.0.1:8080/xmlrpc");

            // 当前进程拥有 SDK HTTP 服务，退出时释放监听和工作线程。
            OrderExampleUtils.awaitShutdown();
        } finally {
            server.shutdown();
        }
    }

    /** XML-RPC struct 转为共享订单模型，保存后返回标准 struct。 */
    public Map<String, Object> save(Map<String, Object> value) {
        Order order = this.mapper.convertValue(value, Order.class);
        return toStruct(this.orders.save(order));
    }

    /** 按标准 struct 中的 id 查询订单。 */
    public Map<String, Object> find(Map<String, Object> value) {
        OrderQuery query = this.mapper.convertValue(value, OrderQuery.class);
        return toStruct(this.orders.find(query));
    }

    private static Map<String, Object> toStruct(Order order) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", order.getId());
        value.put("description", order.getDescription());
        value.put("attachment", order.getAttachment());
        return value;
    }
}
