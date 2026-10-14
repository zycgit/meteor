/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.Properties;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NacosRegistryConfigurationTest {
    @Test
    public void missingPasswordFailsBeforeConnecting() {
        // 用户名存在但未提供密码，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setUsername("user");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void missingUsernameFailsBeforeConnecting() {
        // 密码存在但未提供用户名，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setPassword("secret");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void emptyUsernameFailsBeforeConnecting() {
        // 用户名为空字符串，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setUsername("");
        configuration.setPassword("secret");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void blankUsernameFailsBeforeConnecting() {
        // 用户名只包含空格，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setUsername(" ");
        configuration.setPassword("secret");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void missingAddressFailsBeforeConnecting() {
        // 没有配置服务地址，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setAddress(null);

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry address is required", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void emptyAddressFailsBeforeConnecting() {
        // 服务地址为空字符串，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setAddress("");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry address is required", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void blankAddressFailsBeforeConnecting() {
        // 服务地址只包含空格，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setAddress(" ");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry address is required", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void whitespaceAddressFailsBeforeConnecting() {
        // 服务地址只包含制表符和换行，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setAddress("\t\n");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry address is required", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void missingGroupFailsBeforeConnecting() {
        // 没有提供分组，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setGroup(null);

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Nacos group must not be blank", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void emptyGroupFailsBeforeConnecting() {
        // 分组为空字符串，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setGroup("");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Nacos group must not be blank", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void blankGroupFailsBeforeConnecting() {
        // 分组只包含空格，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setGroup(" ");

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Nacos group must not be blank", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void zeroTimeoutFailsBeforeConnecting() {
        // 配置读取超时为零，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setTimeoutMillis(0);

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("timeoutMillis must be positive", failure.getMessage());
            sdk.verifyNoInteractions();
        }
    }

    @Test
    public void negativeTimeoutFailsBeforeConnecting() {
        // 配置读取超时为负数，其余连接参数有效。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setTimeoutMillis(-1);

        // 参数校验在创建任何 SDK 客户端之前失败。
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("timeoutMillis must be positive", failure.getMessage());
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

            // 从正式扩展获取管理服务，并核对 SDK 参数和公开描述。
            MetConfiguration configuration = new MetConfiguration().addExtension(bootstrap);
            try (MetContext management = configuration.buildContext()) {
                RegistryAdmin admin = management.getExtension(RegistryAdmin.class);
                RegistryInfo info = admin.getClusterAdmin().info();
                sdk.verify(() -> NacosFactory.createNamingService(any(Properties.class)));
                sdk.verify(() -> NacosFactory.createConfigService(any(Properties.class)));
                assertEquals("nacos", info.getProvider());
                assertEquals("orders", info.getScope().get("group"));
                assertEquals("tenant-a", info.getScope().get("namespace"));
                assertFalse(info.getScope().containsKey("password"));
            }

            // 上下文关闭后，两种 SDK 资源一并释放。
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
            bootstrap.setAddress("nacos.example:8848");
            bootstrap.setManagementToken("secret");

            // 配置客户端失败后关闭已创建的 Naming 客户端。
            NacosException actual = assertThrows(NacosException.class, bootstrap::openStorage);
            assertSame(failure, actual);
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

            // 直接打开存储并执行一次已知服务点查。
            try (StorageAccess storage = configuration.openStorage()) {
                assertNull(storage.getService("missing"));
                verify(configs).getConfig(anyString(), eq("orders"), eq(2000L));
            }

            // 存储会话关闭后，两种 SDK 资源一并释放。
            verify(naming).shutDown();
            verify(configs).shutDown();
        }
    }

    @Test
    public void defaultGroupAndTimeoutReachSdk() throws Exception {
        // 只填写必需地址，保留分组、命名空间和超时的默认值。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            ConfigService configs = mock(ConfigService.class);
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertNull(properties.getProperty("namespace"));
                return naming;
            });
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(configs);

            // 点查使用正式默认分组和默认读取超时。
            try (StorageAccess storage = configuration.openStorage()) {
                storage.getService("missing");
                verify(configs).getConfig(anyString(), eq("meteor"), eq(5000L));
            }
        }
    }

    @Test
    public void emptyPasswordIsPassedToSdk() throws Exception {
        // 空密码是明确配置的凭证，不等同于没有提供密码。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        configuration.setUsername("user");
        configuration.setPassword("");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            ConfigService configs = mock(ConfigService.class);
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertEquals("user", properties.getProperty("username"));
                assertEquals("", properties.getProperty("password"));
                return naming;
            });
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(configs);

            // 校验和打开都允许空密码，读取仍走实际存储适配器。
            try (StorageAccess storage = configuration.openStorage()) {
                assertNull(storage.getService("missing"));
            }
        }
    }

    @Test
    public void addressWhitespaceIsRemovedBeforeOpeningSdk() throws Exception {
        // 地址前后允许有粘贴配置时带入的空白。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("  nacos.example:8848  ");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            ConfigService configs = mock(ConfigService.class);
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenAnswer(call -> {
                Properties properties = call.getArgument(0);
                assertEquals("nacos.example:8848", properties.getProperty("serverAddr"));
                return naming;
            });
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(configs);

            // 传给 SDK 的地址已去除空白，存储能够正常打开。
            try (StorageAccess storage = configuration.openStorage()) {
                assertNull(storage.getService("missing"));
            }
        }
    }

    @Test
    public void namingOpenFailureDoesNotCreateConfigClient() throws Exception {
        // 第一个 SDK 在打开时失败。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NacosException unavailable = new NacosException(500, "naming unavailable");
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenThrow(unavailable);

            // 原始错误直接传播，不继续打开配置客户端。
            NacosException failure = assertThrows(NacosException.class, configuration::openStorage);
            assertSame(unavailable, failure);
            sdk.verify(() -> NacosFactory.createConfigService(any(Properties.class)), never());
        }
    }

    @Test
    public void configOpenFailureRetainsNamingCleanupFailure() throws Exception {
        // 第二个 SDK 初始化失败，并让第一个 SDK 的清理也失败。
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.example:8848");
        try (MockedStatic<NacosFactory> sdk = mockStatic(NacosFactory.class)) {
            NamingService naming = mock(NamingService.class);
            NacosException closeFailure = new NacosException(500, "shutdown failed");
            doThrow(closeFailure).when(naming).shutDown();
            sdk.when(() -> NacosFactory.createNamingService(any(Properties.class))).thenReturn(naming);
            NacosException configFailure = new NacosException(500, "config unavailable");
            sdk.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenThrow(configFailure);

            // 初始化错误仍是主异常，清理失败作为 suppressed 原因保留。
            NacosException failure = assertThrows(NacosException.class, configuration::openStorage);
            assertSame(configFailure, failure);
            assertArrayEquals(new Throwable[] { closeFailure }, failure.getSuppressed());
        }
    }
}
