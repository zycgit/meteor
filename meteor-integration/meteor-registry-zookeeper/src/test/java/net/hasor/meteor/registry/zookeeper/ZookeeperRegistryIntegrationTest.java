/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.client.RegistryClient;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ZookeeperRegistryIntegrationTest {

    @MetService(group = "integration")
    public interface Echo {

        String echo(String value);
    }

    @Test
    public void discoversServicesAndTracksProviderOfflineAndOnline() throws Exception {
        // 通过扩展接入嵌入式 ZooKeeper，验证发现地址后的真实 RPC。
        try (TestingServer registry = new TestingServer()) {
            MetConfiguration serverConfig = new MetConfiguration();
            serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration clientConfig = new MetConfiguration();
            clientConfig.connector("tcp").protocol("rsf");
            ZookeeperRegistryConfiguration bootstrap = new ZookeeperRegistryConfiguration();
            bootstrap.setAddress(registry.getConnectString());
            bootstrap.setRefreshMillis(60000);

            serverConfig.addExtension(bootstrap);
            clientConfig.addExtension(bootstrap);
            try (MetContext server = serverConfig.buildContext(); //
                 MetContext client = clientConfig.buildContext()) {
                String id = server.publisher().rsfService(Echo.class, value -> "zk:" + value).register().getBindID();
                client.publisher().rsfService(Echo.class).register();
                server.start();
                client.start();
                server.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
                client.getExtension(RegistryClient.class).pullSync().get(10, TimeUnit.SECONDS);
                assertEquals("zk:hello", client.getRsfClient().wrapper(Echo.class).echo("hello"));

                // 应用下线通过存储订阅撤下地址，无需手动 pull。
                server.offline();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!client.getUpdater().queryAvailableAddresses(id).isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(0, client.getUpdater().queryAvailableAddresses(id).size());

                // 应用重新上线后恢复地址与真实远程调用。
                server.online();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (client.getUpdater().queryAvailableAddresses(id).isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                var restoredAddresses = client.getUpdater().queryAvailableAddresses(id);
                assertFalse("Provider did not return online", restoredAddresses.isEmpty());
                assertEquals("zk:back", client.getRsfClient().wrapper(Echo.class).echo("back"));
            }
        }
    }
}
