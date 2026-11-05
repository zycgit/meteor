/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider.remote;
import java.net.URI;
import java.util.Map;
import net.hasor.config.Configuration;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;

/** 创建 Console 间管理连接，连接只拥有新建的出站 RPC 上下文。 */
@Configuration
public final class RemoteProviderFactory implements ProviderFactory {
    @Override
    public String name() {
        return "remote";
    }

    @Override
    public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
        profile.validate();
        MetConfiguration configuration = new MetConfiguration().setApplicationName("meteor-console-client");
        configuration.setDefaultTimeout(5000);
        configuration.setConnectTimeout(3000);
        configuration.connector("tcp").protocol("rsf");
        MetContext context = configuration.buildContext();

        try {
            JsonUtils.configureRpc(context);
            context.publisher().rsfService(RpcManagement.class).asShadow().asDistributedCall().register();
            context.start();
            InterAddress address = new InterAddress(URI.create(profile.getAddress()));
            Map<String, String> options = Map.of(RpcManagement.CREDENTIAL_OPTION, profile.getManagementToken());
            var client = context.getRsfClient(address).withOptions(options);
            RegistryAdmin admin = new RemoteConnection(context, client.wrapper(RpcManagement.class), profile.getManagementToken());
            return ProviderConnection.registry(admin);
        } catch (Exception | Error failure) {
            try {
                context.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
