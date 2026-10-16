/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceMember;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConsumerProtocolTest {
    @Test
    public void discoveryPublishesProtocolsWithoutInvokingService() throws Exception {
        // 消费者配置了 RSF，但没有提供者时地址本为空。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(50);
        MetConfiguration consumerConfig = new MetConfiguration().addExtension(registry);
        consumerConfig.connector("tcp").protocol("rsf");
        MetConfiguration providerConfig = new MetConfiguration().addExtension(registry);
        providerConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

        try (MetContext consumer = consumerConfig.buildContext()) {
            String id = consumer.publisher().rsfService(Echo.class).register().getBindID();
            consumer.start();
            RegistryAdmin registryAdmin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = registryAdmin.getServiceAdmin(id);
            MetServiceMember member = service.consumers(null, null).getItems().get(0);
            assertEquals(1, member.getEndpoints().length);
            assertArrayEquals(new String[0], member.getUsedProtocols());

            // 提供者上线后自动发现地址并上报协议，全程不发起业务 RPC。
            try (MetContext provider = providerConfig.buildContext()) {
                provider.publisher().rsfService(Echo.class, value -> value).register();
                provider.start();
                RegistryTestUtils.await(() -> {
                    MetServiceMember consumerMember = service.consumers(null, null).getItems().get(0);
                    String[] protocols = consumerMember.getUsedProtocols();
                    return Arrays.equals(new String[] { "rsf" }, protocols);
                });
                assertEquals(1, consumer.getUpdater().queryAllAddresses(id).size());
            }

            // 提供者离开后协议自动清空，不保留历史使用记录。
            RegistryTestUtils.await(() -> {
                MetServiceMember consumerMember = service.consumers(null, null).getItems().get(0);
                return consumerMember.getUsedProtocols().length == 0;
            });
            assertTrue(consumer.getUpdater().queryAllAddresses(id).isEmpty());
        }
    }

    @Test
    public void staticAddressProtocolsAreDeduplicatedAndScopedToService() throws Exception {
        // 不连接业务端点，只读取各服务的静态地址本。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(50);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            String first = context.publisher().rsfService(Echo.class).name("first").register().getBindID();
            String second = context.publisher().rsfService(Echo.class).name("second").register().getBindID();
            context.start();
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(first);
            MetServiceAdmin other = context.getExtension(RegistryAdmin.class).getServiceAdmin(second);
            InterAddress rsf = new InterAddress("rsf://127.0.0.1:19001/default");
            InterAddress alternate = new InterAddress("custom://127.0.0.1:19002/default");
            InterAddress duplicate = new InterAddress("rsf://127.0.0.1:19003/default");
            context.getUpdater().appendStaticAddress(first, List.of(rsf, alternate, duplicate));

            // 周期上报本地地址变更；协议取自地址本，不由连接器配置推断。
            RegistryTestUtils.await(() -> {
                MetServiceMember consumerMember = service.consumers(null, null).getItems().get(0);
                String[] protocols = consumerMember.getUsedProtocols();
                return Arrays.equals(new String[] { "custom", "rsf" }, protocols);
            });
            MetServiceMember otherMember = other.consumers(null, null).getItems().get(0);
            assertArrayEquals(new String[0], otherMember.getUsedProtocols());

            // 单服务推送同时反映删除，不影响其他订阅关系。
            context.getUpdater().removeAddress(first, alternate);
            context.getExtension(RegistryClient.class).pushSync(first).get(5, TimeUnit.SECONDS);
            MetServiceMember updatedMember = service.consumers(null, null).getItems().get(0);
            assertArrayEquals(new String[] { "rsf" }, updatedMember.getUsedProtocols());
            MetServiceMember unchangedMember = other.consumers(null, null).getItems().get(0);
            assertArrayEquals(new String[0], unchangedMember.getUsedProtocols());
        }
    }

    @Test
    public void directConsumerQueryReadsCurrentAddressBook() throws Exception {
        // 通过真正的管理 RPC 查询消费者，不依赖注册中心中的订阅快照。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            String id = context.publisher().rsfService(Echo.class).register().getBindID();
            context.start();
            context.getUpdater().appendStaticAddress(id, List.of(new InterAddress("rsf://127.0.0.1:19001/default")));
            TargetAdmin target = context.getExtension(TargetAdmin.class);
            MetServiceAdmin service = target.getServiceAdminByAddress(context.getListenAddress("rsf"), id);
            MetServiceMember member = service.consumers(null, null).getItems().get(0);
            assertArrayEquals(new String[] { "rsf" }, member.getUsedProtocols());
        }
    }
}
