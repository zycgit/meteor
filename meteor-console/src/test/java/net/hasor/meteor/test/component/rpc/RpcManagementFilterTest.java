/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.component.rpc;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.component.rpc.RpcManagementFilter;
import net.hasor.meteor.console.component.rpc.RpcManagementImpl;
import net.hasor.meteor.console.component.rpc.RpcTarget;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实管理 RPC 验证本机凭据脱敏、错误状态和后续调用隔离。 */
public class RpcManagementFilterTest {
    @ParameterizedTest(name = "RPC {0} 隐藏本机凭据并保留状态")
    @ValueSource(ints = { 400, 409 })
    public void localCredentialsAreRedactedWithoutChangingStatusOrConnection(int expectedStatus) throws Exception {
        // 使用实际注册中心和 RPC 服务，只在管理过滤链中注入一次存储失败。
        String password = "local-storage-password";
        String downstreamToken = "downstream-console-token";
        String managementToken = "inbound-console-token";
        String message = "Storage failure: password=" + password + ", downstream=" + downstreamToken;
        message += ", management=" + managementToken;
        RuntimeException injected;
        if (expectedStatus == 400) {
            injected = new IllegalArgumentException(message);
        } else {
            injected = new UnsupportedOperationException(message);
        }
        AtomicBoolean failOnce = new AtomicBoolean(true);
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-registry-token");
        MetConfiguration serverConfiguration = new MetConfiguration().setApplicationName("console-redaction-server");
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfiguration = new MetConfiguration().setApplicationName("console-redaction-client");
        clientConfiguration.connector("tcp").protocol("rsf");
        try (ProviderConnection connection = ProviderConnection.registry(storage.buildAdmin()); //
             MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            JsonUtils.configureRpc(server);
            JsonUtils.configureRpc(client);
            RpcManagementFilter authentication = new RpcManagementFilter(() -> managementToken, password, downstreamToken, null, "");
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection)) //
                    .bindFilter("console-auth", authentication) //
                    .bindFilter("storage-failure", (request, response, chain) -> {
                        if (failOnce.getAndSet(false)) {
                            throw injected;
                        }
                        chain.doFilter(request, response);
                    }) //
                    .asShadow().asDistributedCall().register();
            server.start();
            client.publisher().rsfService(RpcManagement.class).asShadow().asDistributedCall().register();
            client.start();

            // 原始 RPC 客户端没有本机存储凭据，验证服务端边界完成脱敏。
            Map<String, String> options = Map.of(RpcManagement.CREDENTIAL_OPTION, managementToken);
            RpcManagement rpc = client.getRsfClient(server.getListenAddress("rsf")).withOptions(options) //
                    .wrapper(RpcManagement.class);
            Throwable failure = assertThrows(Exception.class, () -> rpc.registryInfo(RpcTarget.cluster()));
            MetException rejected = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(expectedStatus, rejected.getStatus());
            String visible = rejected.getMessage();
            assertTrue(visible.contains("Storage failure: password=***, downstream=***, management=***"), visible);
            assertFalse(visible.contains(password), visible);
            assertFalse(visible.contains(downstreamToken), visible);
            assertFalse(visible.contains(managementToken), visible);

            // 同一代理和连接继续调用成功，前一次失败不改变实际注册中心或认证状态。
            assertNotNull(rpc.registryInfo(RpcTarget.cluster()));
        }
    }
}
