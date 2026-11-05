package net.hasor.meteor.test.scenario;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.directory.InstancesRequest;
import net.hasor.meteor.console.component.commands.dto.instance.InstanceInfoRequest;
import net.hasor.meteor.console.component.commands.dto.instance.SetInstanceBlockedRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.FetchMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.PutMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.RemoveMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.rule.SetRuleEnabledRequest;
import net.hasor.meteor.console.component.commands.dto.rule.UpdateRuleRequest;
import net.hasor.meteor.console.component.commands.dto.rule.ValidateRuleRequest;
import net.hasor.meteor.console.component.commands.dto.service.MembersRequest;
import net.hasor.meteor.console.component.commands.dto.service.ServiceInfoRequest;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.component.provider.remote.RemoteProviderFactory;
import net.hasor.meteor.console.component.rpc.RpcManagement;
import net.hasor.meteor.console.component.rpc.RpcManagementFilter;
import net.hasor.meteor.console.component.rpc.RpcManagementImpl;
import net.hasor.meteor.console.component.rpc.RpcTarget;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 命令通过真实 RSF 管理远端 Provider，验证作用域、凭据和生命周期。 */
public class ConsoleRemoteScenarioTest {
    private static final RemoteProviderFactory REMOTE = new RemoteProviderFactory();
    @TempDir
    public               Path                  temporary;

    @MetService(group = "console-tests", version = "1")
    public interface Echo {
        String echo(String value);
    }

    private static MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setApplicationName("console-test");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    private static ClusterProfile profile(MetContext server, String token) {
        ClusterProfile profile = new ClusterProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName("Remote Console");
        profile.setType("remote");
        profile.setAddress(server.getListenAddress("rsf").toString());
        profile.setManagementToken(token);
        return profile;
    }

    private static ManagementTarget target(String scope) {
        ManagementTarget target = new ManagementTarget();
        target.setScope(scope);
        return target;
    }

    private Hasor commandContainer() {
        return Hasor.create().registerShutdownHook(false).addModules(binder -> {
                    binder.bindType(MeteorConfig.class).asEagerSingleton();
                    binder.bindType(ClusterDao.class).asEagerSingleton();
                    binder.bindType(ConsoleCookies.class).asEagerSingleton();
                    binder.bindType(SessionService.class).asEagerSingleton();
                    binder.bindType(ProviderFactories.class);
                    binder.bindType(RemoteProviderFactory.class).toInstance(REMOTE);
                    binder.bindType(CommandDispatcher.class);
                }).addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false) //
                .addSettings(Settings.DefaultNameSpace, "data.directory", this.temporary.toString()) //
                .addSettings(Settings.DefaultNameSpace, "security.enable", false);
    }

    @Test
    public void clusterMetadataAndRulesAreExecutedByRemoteConsole() throws Exception {
        // Provider 接管存储会话，RPC 直接发布其注册中心管理接口。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             RegistryAdmin observer = storage.buildAdmin(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            JsonUtils.configureRpc(server);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            ManagementTarget target = target("cluster");
            var info = dispatcher.execute(client.getId(), new ClusterInfoRequest(target));
            assertEquals("memory", info.info().getProvider());
            assertTrue(info.status().isReachable());
            assertTrue(dispatcher.execute(client.getId(), new InstancesRequest(target)).items().isEmpty());

            // 远端写入后独立观察存储，嵌套 Map 的 null 值与 List 的 null 元素均保留。
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("label", "remote");
            nested.put("nullable", null);
            nested.put("items", Arrays.asList("first", null));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("number", 42);
            values.put("enabled", true);
            values.put("nested", nested);
            dispatcher.execute(client.getId(), new PutMetadataRequest(target, values));
            Map<String, Object> stored = observer.getClusterAdmin().fetchMeta();
            assertEquals(values, stored);
            Map<?, ?> storedNested = assertInstanceOf(Map.class, stored.get("nested"));
            assertTrue(storedNested.containsKey("nullable"));
            assertNull(storedNested.get("nullable"));
            assertEquals(Arrays.asList("first", null), storedNested.get("items"));
            assertEquals(values, dispatcher.execute(client.getId(), new FetchMetadataRequest(target)).values());

            // 本地直接写入另一份数据，反向读取必须保留 null，不能仅依赖请求侧的回显。
            Map<String, Object> localNested = new LinkedHashMap<>();
            localNested.put("nullable", null);
            localNested.put("items", Arrays.asList(null, "last"));
            observer.getClusterAdmin().putMeta("local", localNested);
            values.put("local", localNested);
            var fetched = dispatcher.execute(client.getId(), new FetchMetadataRequest(target));
            assertEquals(values, fetched.values());
            Map<?, ?> fetchedNested = assertInstanceOf(Map.class, fetched.values().get("local"));
            assertTrue(fetchedNested.containsKey("nullable"));
            assertNull(fetchedNested.get("nullable"));
            assertEquals(Arrays.asList(null, "last"), fetchedNested.get("items"));
            dispatcher.execute(client.getId(), new RemoveMetadataRequest(target, new String[] { "number" }));
            assertFalse(observer.getClusterAdmin().fetchMeta().containsKey("number"));

            String rule = "<controlSet/>";
            var validationRequest = new ValidateRuleRequest(target, MetRuleType.FLOW_CONTROL, rule);
            assertTrue(dispatcher.execute(client.getId(), validationRequest).valid());
            dispatcher.execute(client.getId(), new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, rule));
            dispatcher.execute(client.getId(), new SetRuleEnabledRequest(target, MetRuleType.FLOW_CONTROL, false));
            assertEquals(rule, observer.getClusterAdmin().fetchRule(MetRuleType.FLOW_CONTROL));
            assertFalse(observer.getClusterAdmin().isRuleEnabled(MetRuleType.FLOW_CONTROL));
        }
    }

    @ParameterizedTest(name = "拒绝非法顶层元信息：{0}")
    @ValueSource(strings = { "null-value", "empty-key" })
    public void invalidTopLevelMetadataIsRejectedWithoutChangingStorage(String invalidField) throws Exception {
        // 使用真实 Remote Provider，非法顶层字段必须保留注册中心 SDK 的参数校验语义。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             RegistryAdmin observer = storage.buildAdmin(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            JsonUtils.configureRpc(server);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection)) //
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret")) //
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            ManagementTarget target = target("cluster");
            Map<String, Object> original = Map.of("owner", "before");
            observer.getClusterAdmin().putMeta(original);
            Map<String, Object> invalid = new LinkedHashMap<>();
            invalid.put("new-field", "must-not-be-written");
            if (StringUtils.equals(invalidField, "null-value")) {
                invalid.put("nullable", null);
            } else {
                invalid.put("", "invalid-key");
            }

            // 整个写入被拒绝，之前的合法项与同批次的合法字段都不能发生部分修改。
            Throwable failure = assertThrows(Exception.class, () -> {
                dispatcher.execute(client.getId(), new PutMetadataRequest(target, invalid));
            });
            assertInstanceOf(IllegalArgumentException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(original, observer.getClusterAdmin().fetchMeta());
            assertEquals(original, dispatcher.execute(client.getId(), new FetchMetadataRequest(target)).values());
        }
    }

    @Test
    public void serviceDetailsAndInstanceScopesRoundTripThroughConsole() throws Exception {
        // 业务实例照常使用核心注册中心，命令通过远端 Provider 组合治理视图。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        MetConfiguration businessConfiguration = serverConfiguration().addExtension(storage);
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext business = businessConfiguration.buildContext(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            String serviceId = business.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            business.start();
            business.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            ManagementTarget service = target("service");
            service.setServiceId(serviceId);
            service.setInstanceId(business.getInstanceID());
            var detail = dispatcher.execute(client.getId(), new ServiceInfoRequest(service));
            assertEquals(serviceId, detail.info().getServiceId());
            assertFalse(detail.description().getJavaType().isEmpty());
            var members = dispatcher.execute(client.getId(), new MembersRequest(service, MetServiceType.Provider));
            assertEquals(1, members.items().size());

            ManagementTarget wholeService = target("service");
            wholeService.setServiceId(serviceId);
            dispatcher.execute(client.getId(), new PutMetadataRequest(service, Map.of("instance", true)));
            dispatcher.execute(client.getId(), new PutMetadataRequest(wholeService, Map.of("service", 42)));
            var instanceMetadata = dispatcher.execute(client.getId(), new FetchMetadataRequest(service));
            assertEquals(Map.of("instance", true), instanceMetadata.values());
            var serviceMetadata = dispatcher.execute(client.getId(), new FetchMetadataRequest(wholeService));
            assertEquals(Map.of("service", 42), serviceMetadata.values());
            ManagementTarget instance = target("instance");
            instance.setInstanceId(business.getInstanceID());
            dispatcher.execute(client.getId(), new SetInstanceBlockedRequest(instance, true));
            assertTrue(local.getInstanceAdmin(business.getInstanceID()).isBlock());
        }
    }

    @Test
    public void batchGovernanceThroughRemoteProviderKeepsOtherUnitsUnchanged() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        MetConfiguration east = serverConfiguration().setUnitName("east").addExtension(storage);
        MetConfiguration eastReplica = serverConfiguration().setUnitName("east").addExtension(storage);
        MetConfiguration west = serverConfiguration().setUnitName("west").addExtension(storage);
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext first = east.buildContext(); //
             MetContext second = eastReplica.buildContext(); //
             MetContext otherUnit = west.buildContext(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            String serviceId = first.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            second.publisher().rsfService(Echo.class, value -> value).register();
            otherUnit.publisher().rsfService(Echo.class, value -> value).register();
            first.start();
            second.start();
            otherUnit.start();
            first.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);
            second.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);
            otherUnit.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            // 命令枚举 east 单元的目标，通过远端 Provider 完成校验和逐实例写入。
            ManagementTarget target = target("service");
            target.setServiceId(serviceId);
            target.setTargetUnit("east");
            String rule = "<controlSet/>";
            var request = new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, rule);
            var result = dispatcher.execute(client.getId(), request);
            assertEquals(2, result.affected());
            var firstService = local.getServiceAdmin(serviceId, first.getInstanceID());
            var secondService = local.getServiceAdmin(serviceId, second.getInstanceID());
            var otherService = local.getServiceAdmin(serviceId, otherUnit.getInstanceID());
            assertEquals(rule, firstService.fetchRule(MetRuleType.FLOW_CONTROL));
            assertEquals(rule, secondService.fetchRule(MetRuleType.FLOW_CONTROL));
            assertNull(otherService.fetchRule(MetRuleType.FLOW_CONTROL));
        }
    }

    @Test
    public void invalidRemoteRulePreservesBadRequestAndStoredRule() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            // 业务校验错误仍是 400，不因跨过 Console RPC 就变成网关错误。
            ManagementTarget target = target("cluster");
            Throwable failure = assertThrows(Exception.class, () -> {
                dispatcher.execute(client.getId(), new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, "<broken"));
            });
            MetException invalid = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(400, invalid.getStatus());
            assertNull(local.getClusterAdmin().fetchRule(MetRuleType.FLOW_CONTROL));
        }
    }

    @Test
    public void retainedClientRechecksServerCredentialAfterRotation() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        AtomicReference<String> expected = new AtomicReference<>("initial-令牌");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(expected::get))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "initial-令牌");
            commandContainer.getInstance(ClusterDao.class).save(client);
            dispatcher.execute(client.getId(), new ClusterInfoRequest(target("cluster")));
            expected.set("rotated");
            Throwable failure = assertThrows(Exception.class, () -> {
                dispatcher.execute(client.getId(), new ClusterInfoRequest(target("cluster")));
            });
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            ClusterProfile updated = profile(server, "rotated");
            commandContainer.getInstance(ClusterDao.class).save(updated);
            assertNotNull(dispatcher.execute(updated.getId(), new ClusterInfoRequest(target("cluster"))));
        }
    }

    @Test
    public void remoteCredentialCannotBypassRevokedRegistryCredentials() throws Exception {
        // Console RPC 凭据有效，底层注册中心使用可撤销的独立凭据来源。
        AtomicReference<String> token = new AtomicReference<>("local-admin-token");
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setTokenProvider(token::get);
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            dispatcher.execute(client.getId(), new PutMetadataRequest(target("cluster"), Map.of("owner", "before")));

            // 保持同一条 RPC 连接，撤销底层凭据后写入必须被拒绝。
            token.set(null);
            var deniedWrite = new PutMetadataRequest(target("cluster"), Map.of("owner", "denied"));
            Throwable failure = assertThrows(Exception.class, () -> {
                dispatcher.execute(client.getId(), deniedWrite);
            });
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(403, denied.getStatus());

            // 恢复底层凭据后，同一连接重新可用，失败写入没有落库。
            token.set("renewed");
            var preserved = dispatcher.execute(client.getId(), new FetchMetadataRequest(target("cluster")));
            assertEquals(Map.of("owner", "before"), preserved.values());
        }
    }

    @Test
    public void rawRpcCannotBypassAuthenticationWithNoCredential() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        MetConfiguration rawConfiguration = new MetConfiguration();
        rawConfiguration.connector("tcp").protocol("rsf");
        RegistryAdmin local = storage.buildAdmin();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             MetContext server = serverConfiguration().buildContext(); //
             MetContext raw = rawConfiguration.buildContext()) {
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            raw.publisher().rsfService(RpcManagement.class).asShadow().asDistributedCall().register();
            raw.start();
            RpcManagement rpc = raw.getRsfClient(server.getListenAddress("rsf")).wrapper(RpcManagement.class);
            Throwable failure = assertThrows(Exception.class, () -> {
                rpc.putMeta(RpcTarget.cluster(), Map.of("bypass", true));
            });
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            assertTrue(local.getClusterAdmin().fetchMeta().isEmpty());
        }
    }

    @Test
    public void registryCommandDispatcherRejectsDirectInstanceRequests() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            ManagementTarget request = target("instance");
            request.setDirect(true);
            Throwable failure = assertThrows(Exception.class, () -> {
                dispatcher.execute(client.getId(), new InstanceInfoRequest(request));
            });
            assertInstanceOf(IllegalArgumentException.class, ExceptionUtils.getRootCause(failure));
            assertTrue(local.getClusterAdmin().fetchMeta().isEmpty());
        }
    }

    @Test
    public void rawRpcRejectsMissingRegistryScopeWithValidCredential() throws Exception {
        // RPC 契约不接受直连地址；持有有效凭据也必须提供合法注册中心作用域。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        MetConfiguration rawConfiguration = new MetConfiguration();
        rawConfiguration.connector("tcp").protocol("rsf");
        RegistryAdmin local = storage.buildAdmin();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             MetContext server = serverConfiguration().buildContext(); //
             MetContext raw = rawConfiguration.buildContext()) {
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            raw.publisher().rsfService(RpcManagement.class).asShadow().asDistributedCall().register();
            raw.start();

            // 空作用域经真实 RPC 被拒绝，元信息不能落库。
            Map<String, String> options = Map.of(RpcManagement.CREDENTIAL_OPTION, "secret");
            RpcManagement rpc = raw.getRsfClient(server.getListenAddress("rsf")).withOptions(options)//
                    .wrapper(RpcManagement.class);
            Throwable failure = assertThrows(Exception.class, () -> {
                rpc.putMeta(new RpcTarget(), Map.of("bypass", true));
            });
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(400, denied.getStatus());
            assertTrue(local.getClusterAdmin().fetchMeta().isEmpty());
        }
    }

    @Test
    public void invalidatedRemoteConnectionReopensWithoutClosingServer() throws Exception {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("local-admin-token");
        RegistryAdmin local = storage.buildAdmin();
        Hasor commandBuilder = this.commandContainer();
        try (ProviderConnection connection = ProviderConnection.registry(local); //
             AppContext commandContainer = commandBuilder.build(); //
             MetContext server = serverConfiguration().buildContext()) {
            CommandDispatcher dispatcher = commandContainer.getInstance(CommandDispatcher.class);
            server.publisher().rsfService(RpcManagement.class, new RpcManagementImpl(connection))//
                    .bindFilter("console-auth", new RpcManagementFilter(() -> "secret"))//
                    .asShadow().asDistributedCall().register();
            server.start();
            ClusterProfile client = profile(server, "secret");
            commandContainer.getInstance(ClusterDao.class).save(client);
            assertNotNull(dispatcher.execute(client.getId(), new ClusterInfoRequest(target("cluster"))));

            // 清除缓存只关闭出站连接；相同集群 ID 的下一次命令会重新连接。
            dispatcher.invalidate(client.getId());
            assertNotNull(dispatcher.execute(client.getId(), new ClusterInfoRequest(target("cluster"))));
            assertTrue(local.getClusterAdmin().status().isReachable());
        }
    }
}
