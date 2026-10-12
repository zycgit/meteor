/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.List;
import java.util.concurrent.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 地址维护和同单元优先治理都通过上下文的公开入口执行。 */
public class AddressScenarioTest {
    @Test
    public void preferTheLocalUnitAndFallBackWhenItsProviderIsRemoved() throws Exception {
        // 两个实例提供同一服务，用不同单元和返回值辨认实际目标。
        MetConfiguration eastConfiguration = new MetConfiguration().setUnitName("east");
        eastConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration westConfiguration = new MetConfiguration().setUnitName("west");
        westConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration consumerConfiguration = new MetConfiguration().setUnitName("east");
        consumerConfiguration.connector("tcp").protocol("rsf");
        try (MetContext east = eastConfiguration.buildContext(); //
             MetContext west = westConfiguration.buildContext(); //
             MetContext client = consumerConfiguration.buildContext()) {
            east.publisher().rsfService(Location.class, () -> "east").register();
            west.publisher().rsfService(Location.class, () -> "west").register();
            MetBindInfo<Location> service = client.publisher().rsfService(Location.class).register();
            east.start();
            west.start();
            client.start();

            // 地址本可由注册中心或应用更新；此处显式维护两个真实服务地址。
            InterAddress eastAddress = east.bindAddress("rsf");
            InterAddress westAddress = west.bindAddress("rsf");
            String id = service.getBindID();
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(id, List.of(eastAddress, westAddress));
            String unitRule = """
                    <controlSet>
                        <flowControl type="unit" enable="true">
                            <threshold>0.5</threshold>
                            <exclusions></exclusions>
                        </flowControl>
                    </controlSet>
                    """;
            assertTrue(updater.updateFlowControl(id, unitRule));
            assertEquals(List.of(eastAddress), updater.queryLocalUnitAddresses(id));
            Location remote = client.getRsfClient().getRemote(service);
            assertEquals("east", remote.locate());

            // 撤去同单元提供者后转向另一单元，恢复地址后又优先选本单元。
            updater.removeAddress(id, eastAddress);
            assertEquals("west", remote.locate());
            updater.appendAddress(id, List.of(eastAddress));
            assertEquals("east", remote.locate());
            assertTrue(updater.updateFlowControl(id, ""));
            updater.refreshAddress(id, List.of(westAddress));
            assertEquals(List.of(westAddress), updater.queryAllAddresses(id));
            assertEquals("west", remote.locate());
        }
    }

    @Test(timeout = 20000)
    public void updateAnActiveRateLimitWithoutRecreatingTheClient() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        ExecutorService calls = Executors.newSingleThreadExecutor();

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Location.class, () -> "available").register();
            server.start();
            var subscription = client.publisher().rsfService(Location.class);
            MetBindInfo<Location> service = subscription.bindAddress(server.bindAddress("rsf")).register();
            client.start();

            // 每秒 1 次、60 秒刷新窗口，最初有 60 个配额，消耗后下一次选址需要等待。
            String rule = """
                    <controlSet>
                        <flowControl type="speed" enable="true">
                            <action>service</action>
                            <rate>1</rate>
                            <peak>60</peak>
                            <timeWindow>60000</timeWindow>
                        </flowControl>
                    </controlSet>
                    """;
            MetUpdater updater = client.getUpdater();
            assertTrue(updater.updateFlowControl(service.getBindID(), rule));
            Location remote = client.getRsfClient().getRemote(service);
            for (int index = 0; index < 60; index++) {
                assertEquals("available", remote.locate());
            }
            CountDownLatch submitted = new CountDownLatch(1);
            var waiting = calls.submit(() -> {
                submitted.countDown();
                return remote.locate();
            });
            assertTrue(submitted.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));

            // 运行期解除限速，原来等待的调用继续完成，无需重建代理。
            assertTrue(updater.updateFlowControl(service.getBindID(), ""));
            assertEquals("available", waiting.get(3, TimeUnit.SECONDS));
        } finally {
            calls.shutdownNow();
            assertTrue(calls.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public interface Location {
        String locate();
    }
}