/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.state;
import java.io.IOException;
import java.util.List;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.OrderDemoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 两个协议挂载到同一个提供者，IDL 只转换签名并委托到同一份订单业务。 */
public final class OrderCompositionConfiguration {
    private OrderCompositionConfiguration() {
    }

    public static MetConfiguration provider(OrderProtocol write, OrderProtocol read, boolean discovery) {
        MetConfiguration configuration = new MetConfiguration().setUnitName(DirectRpcExample.UNIT_NAME);
        ConnectorSettings http = null;

        // HTTP 协议共享一个监听端点，通过各自 context 分流；TCP 协议使用独立端点。
        for (OrderProtocol protocol : List.of(write, read)) {
            ConnectorSettings connector;
            if (isHttp(protocol)) {
                if (http == null) {
                    http = configuration.connector("http").bind("127.0.0.1", 0);
                }
                connector = http;
            } else {
                connector = configuration.connector("tcp").bind("127.0.0.1", 0);
            }
            protocol.mount(connector);
        }

        // 注册扩展的内部管理服务仅使用 RSF，不向业务 HTTP 协议发布管理接口。
        if (discovery && write != OrderProtocol.RSF && read != OrderProtocol.RSF) {
            configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        }
        return configuration;
    }

    public static MetConfiguration consumer(OrderProtocol protocol, boolean discovery) {
        MetConfiguration configuration = protocol.consumerConfiguration();
        if (discovery && protocol != OrderProtocol.RSF) {
            configuration.connector("tcp").protocol("rsf");
        }
        return configuration;
    }

    public static void publish(MetContext provider, OrderDemoService orders, //
            OrderProtocol write, OrderProtocol read) throws IOException {
        ConfigurationBuilder<OrderDemoService> ordinary = null;

        // 普通接口只注册一次并累计协议；仅 ONC RPC 桥使用独立服务身份。
        for (OrderProtocol protocol : List.of(write, read)) {
            if (protocol == OrderProtocol.ONCRPC) {
                protocol.publish(provider, orders);
            } else {
                if (ordinary == null) {
                    ordinary = provider.publisher().rsfService(OrderDemoService.class, orders);
                }
                protocol.configure(ordinary);
            }
        }
        if (ordinary != null) {
            ordinary.register();
        }
    }

    private static boolean isHttp(OrderProtocol protocol) {
        return protocol != OrderProtocol.RSF && protocol != OrderProtocol.DUBBO //
                && protocol != OrderProtocol.THRIFT && protocol != OrderProtocol.ONCRPC;
    }
}
