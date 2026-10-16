/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.Properties;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;

/** 测试治理组件关闭后的包内生命周期，不向生产代码开放内部入口。 */
public final class NacosGovernanceAccess {
    private NacosGovernanceAccess() {
    }

    public static void lockAfterClose(NamingService naming, ConfigService configs) throws Exception {
        NacosConfigStore configStore = new NacosConfigStore(configs, "test", 1000);
        NacosNamingStore namingStore = new NacosNamingStore(naming, "test");
        NacosGovernance governance = new NacosGovernance(configStore, namingStore, new Properties(), "test");
        governance.close();
        governance.locked("governance.global", () -> null);
    }
}
