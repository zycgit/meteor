/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider.jdbc;
import net.hasor.config.Configuration;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;

/** 创建 JDBC 注册中心的管理连接，连接拥有新建的管理资源。 */
@Configuration
public final class JdbcProviderFactory implements ProviderFactory {
    @Override
    public String name() {
        return "jdbc";
    }

    @Override
    public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
        profile.validate();
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(new JdbcDataSource(profile));
        configuration.setTokenProvider(credentials);
        RegistryAdmin admin = configuration.buildAdmin();
        return ProviderConnection.registry(admin);
    }
}
