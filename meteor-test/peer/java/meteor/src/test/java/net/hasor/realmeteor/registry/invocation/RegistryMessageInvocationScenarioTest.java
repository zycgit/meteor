/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.invocation;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.BlockingSignalServiceImpl;
import net.hasor.example.protocol.soap.SignalService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 消息调用由方法注解决定，经真实注册发现与三种网络协议验证接收回执生命周期。 */
@ParameterizedClass(autoCloseArguments = false, name = "message/{0}/{1}", quoteTextArguments = false)
@MethodSource("combinations")
@NxnRpc(name = "rsf", version = "1", cases = "message/jdbc/rsf")
@NxnRpc(name = "rsf", version = "1", cases = "message/zookeeper/rsf")
@NxnRpc(name = "rsf", version = "1", cases = "message/nacos/rsf")
@NxnRpc(name = "soap", version = "1.1", cases = "message/jdbc/soap")
@NxnRpc(name = "soap", version = "1.1", cases = "message/zookeeper/soap")
@NxnRpc(name = "soap", version = "1.1", cases = "message/nacos/soap")
@NxnRpc(name = "hprose", cases = "message/jdbc/hprose")
@NxnRpc(name = "hprose", cases = "message/zookeeper/hprose")
@NxnRpc(name = "hprose", cases = "message/nacos/hprose")
public class RegistryMessageInvocationScenarioTest {
    @TempDir
    public Path data;

    private final String backend;
    private final String protocol;

    public RegistryMessageInvocationScenarioTest(String backend, String protocol) {
        this.backend = backend;
        this.protocol = protocol;
    }
    public static Object[][] combinations() {
        return new Object[][] { // 每一行是独立的注册发现与协议编码组合。
                { "jdbc", "rsf" }, //
                { "jdbc", "soap" }, //
                { "jdbc", "hprose" }, //
                { "zookeeper", "rsf" }, //
                { "zookeeper", "soap" }, //
                { "zookeeper", "hprose" }, //
                { "nacos", "rsf" }, //
                { "nacos", "soap" }, //
                { "nacos", "hprose" }, //
        };
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void discoveredMessageAcknowledgesBeforeBusinessFinishesAndRetainsItsPayload() throws Exception {
        // 应用接入同一个真实注册存储，仅提供者监听业务端口，消费端不传固定地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "message-provider", true);
            MetConfiguration outgoing = this.configuration(registry, "message-consumer", false);
            BlockingSignalServiceImpl notifications = new BlockingSignalServiceImpl();
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                provider.start();
                consumer.start();
                var publication = provider.publisher().rsfService(SignalService.class, notifications) //
                        .protocol(this.protocol).aliasName(this.protocol, "RegistrySignalService");
                var subscription = consumer.publisher().rsfService(SignalService.class) //
                        .protocol(this.protocol).aliasName(this.protocol, "RegistrySignalService");
                if (!StringUtils.equals(this.protocol, "soap")) {
                    String coding = StringUtils.equals(this.protocol, "hprose") ? "Hprose" : "Json";
                    publication.serialize(coding);
                    subscription.serialize(coding);
                }
                publication.register();
                MetBindInfo<SignalService> binding = subscription.register();
                AddressSelectionAwaiter.await(consumer, binding, "signal");

                // 业务由已有闩锁夹具保持阻塞；回执 Future 必须在显式放行业务之前完成。
                String notice = "registered-notice-" + this.backend + "-" + this.protocol;
                try {
                    Class<?>[] parameterTypes = { String.class };
                    Object[] arguments = { notice };
                    MetFuture sent = consumer.getRsfClient().asyncInvoke(binding, "signal", parameterTypes, arguments);
                    sent.getData(5, TimeUnit.SECONDS);
                    assertTrue(notifications.awaitEntered(5, TimeUnit.SECONDS), "Message method was not entered");
                    assertFalse(binding.isMessage(), "Method annotation changed the whole service mode");
                    assertFalse(notifications.isFinished(), "Message receipt waited for business completion");
                } finally {
                    notifications.release();
                }

                // 收到回执后仍允许业务继续执行，确认消息参数实际经发现地址送达远端。
                assertTrue(notifications.awaitFinished(5, TimeUnit.SECONDS), "Message business did not finish");
                assertEquals(notice, notifications.getMessage());
            } finally {
                notifications.release();
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean provider) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.setDefaultTimeout(10000);
        configuration.setApplicationName(application);
        configuration.setDataHome(Files.createDirectory(this.data.resolve(application)));
        configuration.addExtension(registry);

        // RSF 同时承担管理出口；HTTP 消息协议使用独立的业务挂载路径。
        var tcp = configuration.connector("tcp");
        if (provider && StringUtils.equals(this.protocol, "rsf")) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("rsf");
        if (!StringUtils.equals(this.protocol, "rsf")) {
            var http = configuration.connector("http");
            if (provider) {
                http.bind("127.0.0.1", 0);
            }
            http.protocol(this.protocol).addressScheme("http").contextPath("/notifications");
        }
        return configuration;
    }
}
