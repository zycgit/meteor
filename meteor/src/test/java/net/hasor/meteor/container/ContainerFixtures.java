/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.domain.ProtocolConfig;

/** 容器单测共享的无资源配置和业务模型。 */
public final class ContainerFixtures {
    private ContainerFixtures() {
    }

    static MetSettings settings() {
        // 只声明容器构建服务时需要的配置，意外调用立即暴露。
        InvocationHandler settingsHandler = (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "getDefaultGroup":
                    return "default";
                case "getDefaultVersion":
                    return "1.0";
                case "getSerializeType":
                    return "Java";
                case "getRequestTimeout":
                case "getDefaultTimeout":
                    return 3000;
                case "getUnitName":
                    return "unit";
                case "getConnectorConfigs":
                    ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Map.of());
                    InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "unit");
                    ConnectorConfig connector = new ConnectorConfig("tcp", address, Map.of(), List.of(protocol), false);
                    return List.of(connector);
                default:
                    throw new AssertionError("Unexpected setting: " + method.getName());
            }
        };

        // 将公开配置接口与取值处理器组合成无资源代理。
        ClassLoader settingsLoader = MetSettings.class.getClassLoader();
        Class<?>[] settingsInterfaces = { MetSettings.class };
        return (MetSettings) Proxy.newProxyInstance(settingsLoader, settingsInterfaces, settingsHandler);
    }

    public interface Echo {
        String echo(String value);
    }

    @MetService(group = "group", name = "echo", version = "2", serializeType = "Json", clientTimeout = 1200)
    public interface AnnotatedEcho extends Echo {
    }

    public static class EchoImpl implements Echo {
        @Override
        public String echo(String value) {
            return value;
        }
    }

    public static class PrivateEcho extends EchoImpl {
        private PrivateEcho() {
        }
    }

    public static class PassFilter implements MetFilter {
        @Override
        public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
            chain.doFilter(request, response);
        }
    }
}
