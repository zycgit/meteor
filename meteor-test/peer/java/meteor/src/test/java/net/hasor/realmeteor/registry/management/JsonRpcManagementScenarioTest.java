/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.management;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 管理 RPC 通过 JSON-RPC 参数传递凭据，不依赖 RSF 或请求头。 */
@NxnRpc(name = "jsonrpc", version = "2.0")
public class JsonRpcManagementScenarioTest {
    @TempDir
    public Path directory;

    @Test
    public void retainedJsonRpcAdminReadsRotatedCredentialOnEveryCall() throws Exception {
        // 两端只为管理服务选择 JSON-RPC，并分别持有可轮换的凭据来源。
        AtomicReference<String> expected = new AtomicReference<>("令牌-initial");
        AtomicReference<String> supplied = new AtomicReference<>(expected.get());
        MetConfiguration incoming = this.configuration(expected::get, true);
        MetConfiguration outgoing = this.configuration(supplied::get, false);
        try (MetContext target = incoming.buildContext(); //
             MetContext caller = outgoing.buildContext()) {
            target.start();
            caller.start();
            assertEquals(Set.of("jsonrpc"), target.getServiceInfo(ManagementRPC.class).getBindProtocols());
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            var instance = targets.getInstanceAdminByAddress(target.getListenAddress("jsonrpc"));
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());

            // 服务端轮换后旧凭据不能写入，更新调用端后复用同一管理对象成功。
            expected.set("rotated");
            assertThrows(Exception.class, instance::block);
            supplied.set("rotated");
            assertFalse(instance.isBlock());
            instance.block();
            assertTrue(instance.isBlock());
            instance.unblock();
            assertFalse(instance.isBlock());
        }
    }

    @Test
    public void jsonRpcRequestHeaderCannotReplaceExplicitCredentialArgument() throws Exception {
        // 即使 HTTP 请求头携带正确的旧选项，缺少参数凭据仍不能修改实例。
        MetConfiguration incoming = this.configuration(() -> "secret", true);
        MetConfiguration outgoing = this.configuration(null, false);
        try (MetContext target = incoming.buildContext(); //
             MetContext caller = outgoing.buildContext()) {
            target.start();
            caller.start();
            ManagementRPC raw = caller.getRsfClient(target.getListenAddress("jsonrpc")) //
                    .withOptions(Map.of("meteor.management.token", "secret")) //
                    .wrapper(ManagementRPC.class);
            assertThrows(Exception.class, () -> raw.setBlock(null, true));

            // 正确的显式参数可以读取并写入，非法请求没有改变阻断状态。
            assertFalse(raw.isBlock("secret"));
            raw.setBlock("secret", true);
            assertTrue(raw.isBlock("secret"));
        }
    }

    private MetConfiguration configuration(RegistryTokenProvider tokens, boolean listen) {
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        registry.setManagementProtocols("jsonrpc");
        registry.setTokenProvider(tokens);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.setDefaultTimeout(3000);
        configuration.setConnectTimeout(1000);
        if (listen) {
            configuration.setDataHome(this.directory.resolve("target"));
        } else {
            configuration.setDataHome(this.directory.resolve("caller"));
        }
        var connector = configuration.connector("http");
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("jsonrpc");
        return configuration;
    }
}
