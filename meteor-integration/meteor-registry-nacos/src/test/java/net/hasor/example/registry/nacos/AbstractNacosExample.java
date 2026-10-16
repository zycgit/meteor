/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.registry.nacos;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.DefaultSettings;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 共享外部 Nacos 配置读取和等待方法；各场景自行管理应用与存储会话。 */
public abstract class AbstractNacosExample {
    protected NacosRegistryConfiguration createRegistryConfiguration() throws Exception {
        // 读取外部连接参数，未提供地址时明确报错。
        Settings settings = new DefaultSettings("classpath:/nacos-test.properties");
        String address = settings.getString("nacos.address");
        if (StringUtils.isBlank(address)) {
            throw new IllegalArgumentException("请在 nacos-test.properties 中配置 nacos.address");
        }
        String namespace = StringUtils.trimToNull(settings.getString("nacos.namespace", "meteor"));
        String username = StringUtils.trimToNull(settings.getString("nacos.username"));
        String password = settings.getString("nacos.password");

        // 每次场景独立分组；这里只构建配置，不打开连接或应用。
        NacosRegistryConfiguration registry = new NacosRegistryConfiguration();
        registry.setAddress(address);
        registry.setNamespace(namespace);
        registry.setUsername(username);
        registry.setPassword(password);
        String group = "meteor-test-" + UUID.randomUUID();
        registry.setGroup(group);
        System.out.println("Nacos 场景分组：" + group + "（持久档案保留供检查）");
        registry.setManagementToken("scenario-admin");
        registry.setRefreshMillis(60000);
        return registry;
    }

    protected static void await(String message, Callable<Boolean> condition) throws Exception {
        // 每轮重新观察状态，在有限时间内等待异步登记或订阅完成。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(message);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

}
