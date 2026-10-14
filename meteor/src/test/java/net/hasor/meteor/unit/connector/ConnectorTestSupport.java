/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;

/** 单元测试的上下文替身；动态代理只实现公开接口，不反射读取私有状态。 */
final class ConnectorTestSupport {
    private ConnectorTestSupport() {
    }

    static MetContext sharedContext(ConnectorConfig... configs) {
        return sharedContext(ConnectorTestSupport.class.getClassLoader(), configs);
    }

    static MetContext sharedContext(ClassLoader loader, ConnectorConfig... configs) {
        // 仅提供内核装配所需的公开上下文契约，不读取实现私有字段。
        JavaSerializeCoder coder = new JavaSerializeCoder();
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getConnectorConfigs", method.getName())) {
                return Arrays.asList(configs);
            }
            if (StringUtils.equals("getDefaultTimeout", method.getName())) {
                return 3000;
            }
            throw new AssertionError("Unexpected setting: " + method);
        };
        Class<?>[] settingsTypes = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingsTypes, settingsHandler);
        // 序列化和服务查询保持最小响应，未声明的访问立即暴露测试依赖。
        InvocationHandler contextHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getSerializeCoder":
                    return ObjectUtils.equals("Java", args[0]) ? coder : null;
                case "getServiceIDs":
                    return Collections.emptyList();
                case "getServiceInfo":
                    return null;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        };
        return (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, contextHandler);
    }

}
