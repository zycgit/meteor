/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.Arrays;
import java.util.Properties;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.EntityType;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NacosRegistryConfigurationTest {
    @Test
    public void incompleteStorageCredentialsFailBeforeConnecting() {
        // 配置不完整时直接拒绝，不尝试建立外部连接。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        String[][] invalid = { { "user", null }, { null, "secret" }, { "", "secret" }, { " ", "secret" } };
        for (String[] credentials : invalid) {
            configuration.setUsername(credentials[0]);
            configuration.setPassword(credentials[1]);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
        }
    }

    @Test
    public void addressIsRequiredBeforeConnecting() {
        // 地址没有本机默认值，空值与空白均在连接前报错。
        NacosRegistryConfiguration config = new NacosRegistryConfiguration();
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            for (String address : Arrays.asList(null, "", " ", "\t\n")) {
                config.setAddress(address);
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::openStorage);
                assertEquals("Registry address is required", failure.getMessage());
            }
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void invalidGroupAndTimeoutFailBeforeCreatingSdkClients() {
        // 非法分组及超时不创建任何 Nacos SDK 客户端。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            for (String group : Arrays.asList(null, "", " ")) {
                configuration.setGroup(group);
                assertThrows(IllegalArgumentException.class, configuration::openStorage);
            }
            configuration.setGroup("METEOR").setTimeoutMillis(0);
            assertThrows(IllegalArgumentException.class, configuration::openStorage);
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void configurationPassesProviderAddressAndCredentialsToBothNacosClients() throws Exception {
        // 通过扩展获取管理能力，观察 SDK 接收到的存储连接参数。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            ConfigService configs = mock(ConfigService.class);
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertEquals("nacos.example:8848", properties.getProperty("serverAddr"));
                assertEquals("admin-user", properties.getProperty("username"));
                assertEquals("storage-secret", properties.getProperty("password"));
                assertEquals("tenant-a", properties.getProperty("namespace"));
                assertNull(properties.getProperty("managementToken"));
                return naming;
            });

            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertEquals("nacos.example:8848", properties.getProperty("serverAddr"));
                assertEquals("admin-user", properties.getProperty("username"));
                assertEquals("storage-secret", properties.getProperty("password"));
                return configs;
            });
            NacosRegistryConfiguration bootstrap = new NacosRegistryConfiguration();
            bootstrap.setAddress("nacos.example:8848");
            bootstrap.setUsername("admin-user");
            bootstrap.setPassword("storage-secret");
            bootstrap.setManagementToken("api-secret");
            bootstrap.setNamespace("tenant-a");
            bootstrap.setGroup("orders");
            bootstrap.setTimeoutMillis(2000);
            try (MetContext management = new MetConfiguration().addExtension(bootstrap).buildContext()) {
                RegistryAdmin admin = management.getExtension(RegistryAdmin.class);
                RegistryInfo info = admin.getClusterAdmin().info();
                sdk.verify(() -> NacosFactory.createNamingService(any(Properties.class)));
                sdk.verify(() -> NacosFactory.createConfigService(any(Properties.class)));
                assertEquals("nacos", info.getProvider());
                assertEquals("orders", info.getScope().get("group"));
                assertEquals("tenant-a", info.getScope().get("namespace"));
                assertFalse(info.getScope().containsKey("password"));
            }
            verify(naming).shutDown();
            verify(configs).shutDown();
        }
    }

    @Test
    public void configurationPropagatesOpenFailureAndClosesPartialStorageResources() throws Exception {
        // 第二个 SDK 初始化失败时保留原始异常，并释放已打开的会话。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            NacosException failure = new NacosException(500, "Cannot open config service");
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenReturn(naming);
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenThrow(failure);

            NacosRegistryConfiguration bootstrap = new NacosRegistryConfiguration();
            bootstrap.setAddress("nacos.example:8848").setManagementToken("secret");
            assertSame(failure, assertThrows(NacosException.class, bootstrap::openStorage));
            verify(naming).shutDown();
        }
    }

    @Test
    public void typedConfigurationOpensStorageWithoutAdminOrContext() throws Exception {
        // 存储契约直接使用配置打开会话，不需要应用或管理 token。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setGroup("orders");
        configuration.setNamespace("tenant-a");
        configuration.setTimeoutMillis(2000);

        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            ConfigService configs = mock(ConfigService.class);
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenReturn(naming);
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertEquals("nacos.example:8848", properties.getProperty("serverAddr"));
                assertEquals("tenant-a", properties.getProperty("namespace"));
                return configs;
            });
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRef missing = new EntityRef(EntityType.SERVICE, "missing");
                assertNull(storage.getEntity(missing));
                verify(configs).getConfig(anyString(), eq("orders"), eq(2000L));
            }
            verify(naming).shutDown();
            verify(configs).shutDown();
        }
    }

}
