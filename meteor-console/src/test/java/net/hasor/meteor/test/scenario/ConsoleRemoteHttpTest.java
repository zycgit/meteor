/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.scenario;
import java.nio.file.Path;
import java.util.Map;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.component.rpc.RpcManagementFilter;
import net.hasor.meteor.console.component.rpc.RpcManagementImpl;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.Api;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.console;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 RSF 和 HTTP 验证远端故障的状态映射与管理凭据脱敏。 */
public class ConsoleRemoteHttpTest {
    @TempDir
    public Path temporary;

    @ParameterizedTest(name = "RPC {0} → HTTP {1}")
    @CsvSource({ "400,400", "401,403", "403,403", "404,404", "409,409", "408,504", "500,502" })
    public void remoteFailuresMapToHttpAndRedactToken(short remoteStatus, int expectedStatus) throws Exception {
        // 远端使用独立的内存注册中心和真实 RSF 监听，控制台通过正式配置启动。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        MetConfiguration configuration = new MetConfiguration().setApplicationName("console-http-errors");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        String token = "remote-management-secret";
        String consoleConfiguration = console(this.temporary);
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(consoleConfiguration);
        RegistryAdmin local = storage.buildAdmin();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             MetContext remote = configuration.buildContext()) {
            // 凭据校验后注入远端失败，让 RPC 状态和敏感消息经过完整管理调用链。
            remote.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> token))//
                    .bindFilter("remote-failure", (request, response, chain) -> {
                        response.sendStatus(remoteStatus, "Remote failure: managementToken=" + token);
                    })//
                    .asShadow().asDistributedCall().register();
            remote.start();
            try (BootApplication application = boot.start()) {
                Api api = new Api(application);
                api.login();
                Map<String, Object> profile = Map.of(//
                        "name", "Remote Console", //
                        "type", "remote", //
                        "address", remote.getListenAddress("rsf").toString(), //
                        "managementToken", token);

                // HTTP 边界保留业务状态，认证失败与网关故障按 Console 的对外契约转换。
                var response = api.send("clusters/test", "POST", profile);
                assertEquals(expectedStatus, response.statusCode(), response.body());
                var error = JsonUtils.readTree(response.body());
                assertEquals(expectedStatus, error.path("status").asInt());
                String message = error.path("error").asText();
                assertFalse(message.contains(token), response.body());
                if (remoteStatus == 401) {
                    assertEquals("Remote Console authentication failed", message);
                } else {
                    assertTrue(message.contains("Remote failure: managementToken=***"), message);
                }
            }
        }
    }
}
