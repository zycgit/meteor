/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.scenario;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.FetchMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.PutMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.RemoveMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.rule.ReadRuleRequest;
import net.hasor.meteor.console.component.commands.dto.rule.SetRuleEnabledRequest;
import net.hasor.meteor.console.component.commands.dto.rule.UpdateRuleRequest;
import net.hasor.meteor.console.component.commands.dto.rule.ValidateRuleRequest;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.component.rpc.RpcManagementFilter;
import net.hasor.meteor.console.component.rpc.RpcManagementImpl;
import net.hasor.meteor.console.component.rpc.RpcServer;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.console;
import static net.hasor.meteor.test.scenario.ConsoleTestSupport.registry;
import static org.junit.jupiter.api.Assertions.*;

/** 正式 Console 节点经独立凭据逐跳转发，最终操作 JDBC 注册中心。 */
public class ConsoleCascadeTest {
    @TempDir
    public Path directory;

    @Test
    public void metadataAndRulesReachJdbcThroughTwoRemoteNodes() throws Exception {
        // C 持有 JDBC，B 连接 C，A 再连接 B；每个节点使用自己的入站凭据。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var storage = registry(url);
        Boot bootC = new Boot().sources(ConsoleMain.class) //
                .hconfigFile(this.configuration("c", "jdbc", url, "token-c", ""));
        try (RegistryAdmin observer = storage.buildAdmin(); //
             BootApplication c = bootC.start()) {
            Boot bootB = new Boot().sources(ConsoleMain.class) //
                    .hconfigFile(this.configuration("b", "remote", address(c), "token-b", "token-c"));
            try (BootApplication b = bootB.start()) {
                Boot bootA = new Boot().sources(ConsoleMain.class) //
                        .hconfigFile(this.configuration("a", "remote", address(b), "token-a", "token-b"));
                try (BootApplication a = bootA.start()) {
                    CommandDispatcher dispatcher = a.getAppContext().getInstance(CommandDispatcher.class);
                    ManagementTarget cluster = cluster();
                    Map<String, Object> values = Map.of("owner", "cascade", "weight", 42, "nested", Map.of("enabled", true));
                    ClusterProfile client = profile(a, "token-a");
                    a.getAppContext().getInstance(ClusterDao.class).save(client);
                    // 从 A 读到真实存储类型，再写入元数据及集群规则并从 JDBC 独立核对。
                    var info = dispatcher.execute(client.getId(), new ClusterInfoRequest(cluster));
                    assertEquals("jdbc", info.info().getProvider());
                    assertTrue(info.status().isReachable());
                    dispatcher.execute(client.getId(), new PutMetadataRequest(cluster, values));
                    var metadata = dispatcher.execute(client.getId(), new FetchMetadataRequest(cluster));
                    assertEquals(values, metadata.values());
                    assertEquals(values, observer.getClusterAdmin().fetchMeta());

                    String rule = "<controlSet/>";
                    var validationRequest = new ValidateRuleRequest(cluster, MetRuleType.FLOW_CONTROL, rule);
                    assertTrue(dispatcher.execute(client.getId(), validationRequest).valid());
                    dispatcher.execute(client.getId(), new UpdateRuleRequest(cluster, MetRuleType.FLOW_CONTROL, rule));
                    var disable = new SetRuleEnabledRequest(cluster, MetRuleType.FLOW_CONTROL, false);
                    dispatcher.execute(client.getId(), disable);
                    assertEquals(rule, observer.getClusterAdmin().fetchRule(MetRuleType.FLOW_CONTROL));
                    assertFalse(observer.getClusterAdmin().isRuleEnabled(MetRuleType.FLOW_CONTROL));
                    var read = new ReadRuleRequest(cluster, MetRuleType.FLOW_CONTROL);
                    var view = dispatcher.execute(client.getId(), read);
                    assertEquals(rule, view.rule());
                    assertFalse(view.enabled());

                    // 关闭上游客户端只释放该连接；原有三个节点可继续完成同一条级联调用。
                    dispatcher.invalidate(client.getId());
                    var reconnected = dispatcher.execute(client.getId(), new FetchMetadataRequest(cluster));
                    assertEquals(values, reconnected.values());
                    dispatcher.execute(client.getId(), new RemoveMetadataRequest(cluster, new String[] { "weight" }));
                    assertFalse(observer.getClusterAdmin().fetchMeta().containsKey("weight"));
                }
            }
        }
    }

    @Test
    public void invalidJdbcRuleKeepsBadRequestAcrossTwoRemoteNodes() throws Exception {
        // 用正式 JDBC 管理能力产生错误，避免由中间节点或测试替身伪造结果。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Boot bootC = new Boot().sources(ConsoleMain.class) //
                .hconfigFile(this.configuration("c", "jdbc", url, "token-c", ""));
        try (BootApplication c = bootC.start()) {
            Boot bootB = new Boot().sources(ConsoleMain.class) //
                    .hconfigFile(this.configuration("b", "remote", address(c), "token-b", "token-c"));
            try (BootApplication b = bootB.start()) {
                Boot bootA = new Boot().sources(ConsoleMain.class) //
                        .hconfigFile(this.configuration("a", "remote", address(b), "token-a", "token-b"));
                try (BootApplication a = bootA.start()) {
                    CommandDispatcher dispatcher = a.getAppContext().getInstance(CommandDispatcher.class);
                    ClusterProfile client = profile(a, "token-a");
                    a.getAppContext().getInstance(ClusterDao.class).save(client);
                    ManagementTarget target = cluster();
                    dispatcher.execute(client.getId(), new PutMetadataRequest(target, Map.of("unchanged", true)));

                    // C 的真实 JDBC 管理接口校验规则正文，400 经过 A、B 仍保持原状态。
                    var invalidRule = new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, "<broken");
                    Throwable failure = assertThrows(Exception.class, () -> {
                        dispatcher.execute(client.getId(), invalidRule);
                    });
                    MetException remote = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
                    assertEquals(400, remote.getStatus());

                    // 业务错误没有污染连接和已保存数据，下一次调用仍通过完整链路。
                    var preserved = dispatcher.execute(client.getId(), new FetchMetadataRequest(cluster()));
                    assertEquals(Map.of("unchanged", true), preserved.values());
                }
            }
        }
    }

    @Test
    public void terminalRpcConflictKeepsStatusAcrossTwoRemoteNodes() throws Exception {
        // C 使用真实内存注册中心；只在已认证的 fetchMeta RPC 上注入终端故障。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        AtomicBoolean conflict = new AtomicBoolean();
        AtomicBoolean terminalReached = new AtomicBoolean();
        MetConfiguration configuration = new MetConfiguration().setApplicationName("cascade-terminal");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        RegistryAdmin observer = storage.buildAdmin();
        try (ProviderConnection connection = ProviderConnection.registry(observer); //
             MetContext c = configuration.buildContext()) {
            c.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection)) //
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "token-c")) //
                    .bindFilter("terminal-conflict", (request, response, chain) -> {
                        if (conflict.get() && StringUtils.equals(request.getMethod().getName(), "fetchMeta")) {
                            terminalReached.set(true);
                            response.sendStatus((short) 409, "Terminal registry conflict");
                            return;
                        }
                        chain.doFilter(request, response);
                    }).asShadow().asDistributedCall().register();
            c.start();

            // B 连接 C，A 连接 B；启动探测和正常写入必须先经过完整链路成功。
            String terminalAddress = c.getListenAddress("rsf").toString();
            Boot bootB = new Boot().sources(ConsoleMain.class) //
                    .hconfigFile(this.configuration("b", "remote", terminalAddress, "token-b", "token-c"));
            try (BootApplication b = bootB.start()) {
                Boot bootA = new Boot().sources(ConsoleMain.class) //
                        .hconfigFile(this.configuration("a", "remote", address(b), "token-a", "token-b"));
                try (BootApplication a = bootA.start()) {
                    CommandDispatcher dispatcher = a.getAppContext().getInstance(CommandDispatcher.class);
                    ClusterProfile client = profile(a, "token-a");
                    a.getAppContext().getInstance(ClusterDao.class).save(client);
                    ManagementTarget target = cluster();
                    dispatcher.execute(client.getId(), new PutMetadataRequest(target, Map.of("unchanged", true)));

                    // 由 C 返回的 409 穿过两跳 RPC，不能被上游改写为 500。
                    conflict.set(true);
                    Throwable failure = assertThrows(Exception.class, () -> {
                        dispatcher.execute(client.getId(), new FetchMetadataRequest(target));
                    });
                    MetException remote = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
                    assertEquals(409, remote.getStatus());
                    assertTrue(terminalReached.get());

                    // 关闭故障后，同一条连接仍能读取原数据并完成下一次写入。
                    conflict.set(false);
                    var preserved = dispatcher.execute(client.getId(), new FetchMetadataRequest(target));
                    assertEquals(Map.of("unchanged", true), preserved.values());
                    dispatcher.execute(client.getId(), new PutMetadataRequest(target, Map.of("recovered", true)));
                    var recovered = dispatcher.execute(client.getId(), new FetchMetadataRequest(target));
                    assertEquals(Map.of("unchanged", true, "recovered", true), recovered.values());
                    assertEquals(recovered.values(), observer.getClusterAdmin().fetchMeta());
                }
            }
        }
    }

    @Test
    public void anotherNodesCredentialCannotReplaceTheNextHopsCredential() throws Exception {
        // B 的出站凭据正确；A 错把 C 的凭据用于访问 B，不应获得逐跳信任。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        registry(url);
        Boot bootC = new Boot().sources(ConsoleMain.class) //
                .hconfigFile(this.configuration("c", "jdbc", url, "token-c", ""));
        try (BootApplication c = bootC.start()) {
            Boot bootB = new Boot().sources(ConsoleMain.class) //
                    .hconfigFile(this.configuration("b", "remote", address(c), "token-b", "token-c"));
            try (BootApplication b = bootB.start()) {
                CommandDispatcher dispatcher = b.getAppContext().getInstance(CommandDispatcher.class);
                // 直接通过真实 RPC 确认跨节点凭据被 B 拒绝，错误明确保留为 401。
                ClusterProfile wrong = profile(b, "token-c");
                b.getAppContext().getInstance(ClusterDao.class).save(wrong);
                Throwable failure = assertThrows(Exception.class, () -> {
                    dispatcher.execute(wrong.getId(), new ClusterInfoRequest(cluster()));
                });
                MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
                assertEquals(401, denied.getStatus());
                dispatcher.invalidate(wrong.getId());

                // 同一错误凭据用于 A 的出站连接时，正式 Boot 启动也必须失败。
                Boot bootA = new Boot().sources(ConsoleMain.class) //
                        .hconfigFile(this.configuration("a", "remote", address(b), "token-a", "token-c"));
                assertThrows(IllegalStateException.class, bootA::start);

                // 失败节点的回滚不能关闭它借助网络访问的 B、C，正确凭据仍可完成写入。
                ClusterProfile client = profile(b, "token-b");
                b.getAppContext().getInstance(ClusterDao.class).save(client);
                dispatcher.execute(client.getId(), new PutMetadataRequest(cluster(), Map.of("after-failure", true)));
                var restored = dispatcher.execute(client.getId(), new FetchMetadataRequest(cluster()));
                assertEquals(Map.of("after-failure", true), restored.values());
                ClusterProfile downstreamProfile = profile(c, "token-c");
                b.getAppContext().getInstance(ClusterDao.class).save(downstreamProfile);
                var downstream = dispatcher.execute(downstreamProfile.getId(), new FetchMetadataRequest(cluster()));
                assertEquals(Map.of("after-failure", true), downstream.values());
            }
        }
    }

    private String configuration(String name, String type, String address, String token, String downstreamToken) throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("hasor.boot.web.connectors.http.enabled", "false");
        overrides.put("rpc.host", "127.0.0.1");
        overrides.put("rpc.port", "0");
        overrides.put("management.token", token);
        overrides.put("storage.type", type);
        overrides.put("storage.address", address);
        overrides.put("storage.database", "h2");
        overrides.put("storage.username", "sa");
        overrides.put("storage.managementToken", downstreamToken);
        return console(this.directory.resolve(name), overrides);
    }

    private static String address(BootApplication application) {
        return application.getAppContext().getInstance(RpcServer.class).getAddress();
    }

    private static ClusterProfile profile(BootApplication application, String token) {
        ClusterProfile profile = new ClusterProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName("cascade-client");
        profile.setType("remote");
        profile.setAddress(address(application));
        profile.setManagementToken(token);
        return profile;
    }

    private static ManagementTarget cluster() {
        ManagementTarget target = new ManagementTarget();
        target.setScope("cluster");
        return target;
    }
}
