/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressPoolXmlTest {
    @MetService
    public interface EchoService {
        String echo(String text);
    }

    @Test
    void loadsConfiguredBookDuringStartup() throws Exception {
        // 原生程序只配置文件位置；服务注册可发生在 buildContext 之后。
        String file = "classpath:address-book/billing-order.xml";
        MetConfiguration configuration = new MetConfiguration().setAddressBook(file);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 注册早于 start。
                    .group("billing").name("order-query").version("1.0").register();
            configuration.setAddressBook("classpath:unused.xml");
            assertEquals(file, context.getSettings().getAddressBook());
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
            context.start();
            assertTrue(context.isOnline());
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28001/example")), context.getUpdater().queryAllAddresses(service.getBindID()));
        }
    }

    @Test
    void closesContextWhenConfiguredBookCannotLoad() throws Exception {
        // 初始地址本失败属于启动失败，上下文不能继续上线。
        MetConfiguration configuration = new MetConfiguration().setAddressBook("classpath:missing-address-book.xml");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            assertThrows(RuntimeException.class, context::start);
            assertFalse(context.isOnline());
            assertThrows(IllegalStateException.class, context::start);
        }
    }

    @Test
    void locatesServiceByRegistrationIdentity() throws Exception {
        // 服务注册名与 Java 接口名无关，地址本不加载接口类型。
        String file = "classpath:address-book/registration-identity.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 显式服务注册信息。
                    .group("billing").name("order-query").version("2026.1").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            List<InterAddress> addresses = context.getUpdater().queryAllAddresses(service.getBindID());
            assertEquals(2, addresses.size());
            assertTrue(addresses.contains(new InterAddress("rsf://127.0.0.1:28001/example")));
            assertTrue(addresses.contains(new InterAddress("rsf://127.0.0.1:28002/example")));
        }
    }

    @Test
    void separatesVersionsOfSameService() throws Exception {
        // 同分组、同名称的两个版本各自拥有独立地址。
        String file = "classpath:address-book/service-versions.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> first = context.publisher().rsfService(EchoService.class) // 第一版服务。
                    .group("billing").name("order-query").version("1.0").register();
            MetBindInfo<EchoService> second = context.publisher().rsfService(EchoService.class) // 第二版服务。
                    .group("billing").name("order-query").version("2.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28001/example")), context.getUpdater().queryAllAddresses(first.getBindID()));
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28002/example")), context.getUpdater().queryAllAddresses(second.getBindID()));
        }
    }

    @Test
    void separatesGroupsOfSameService() throws Exception {
        // 同名同版本的其他分组不会受到当前地址本条目的影响。
        String file = "classpath:address-book/billing-order.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> billing = context.publisher().rsfService(EchoService.class) // 目标分组。
                    .group("billing").name("order-query").version("1.0").register();
            MetBindInfo<EchoService> warehouse = context.publisher().rsfService(EchoService.class) // 另一个分组。
                    .group("warehouse").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(1, context.getUpdater().queryAllAddresses(billing.getBindID()).size());
            assertTrue(context.getUpdater().queryAllAddresses(warehouse.getBindID()).isEmpty());
        }
    }

    @Test
    void preservesStaticAddressesWhenRegistryAddressesRefresh() throws Exception {
        String file = "classpath:address-book/billing-order.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 初始静态地址。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            context.getUpdater().refreshAddress(service.getBindID(), List.of());
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28001/example")), context.getUpdater().queryAllAddresses(service.getBindID()));
        }
    }

    @Test
    void rejectsMissingFile() throws Exception {
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertThrows(FileNotFoundException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), "classpath:missing-address-book.xml"));
        }
    }

    @Test
    void ignoresUnknownVersion() throws Exception {
        // 不存在的服务条目不生效，也不创建新地址桶。
        String file = "classpath:address-book/unknown-version.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 只注册第一版。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(1, context.getServiceIDs().size());
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
            assertNull(context.getUpdater().queryAllAddresses("[billing]order-query-2.0"));
        }
    }

    @Test
    void rejectsMissingVersion() throws Exception {
        String file = "classpath:address-book/missing-version.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("version"));
        }
    }

    @Test
    void emptyAddressListLeavesRegistryDiscoveryAvailable() throws Exception {
        // 空地址列表不会改变连接器支持范围，仍可使用注册发现。
        String file = "classpath:address-book/empty-address-list.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 已有消费接口。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(Set.of("rsf"), context.getUpdater().getSupportedProtocols(service.getBindID()));
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
        }
    }

    @Test
    void rejectsInvalidAddress() throws Exception {
        String file = "classpath:address-book/invalid-address.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(EchoService.class) // 非法地址不能写入地址池。
                    .group("billing").name("order-query").version("1.0").register();
            assertThrows(IllegalArgumentException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
        }
    }

    @Test
    void rejectsExternalEntities() throws Exception {
        String file = "classpath:address-book/external-entity.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
        }
    }

    @Test
    void rejectsUnknownAttributesBeforeUpdatingAnyService() throws Exception {
        // 整份文档先通过 XSD，后一个条目非法时，前一个条目也不能写入地址池。
        String file = "classpath:address-book/unknown-attribute.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 准备可解析的服务。
                    .group("billing").name("order-query").version("1.0").register();
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("unexpected"));
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
        }
    }

    @Test
    void validatesRequiredAttributesWithoutSchemaLocation() throws Exception {
        // 即使文件未声明 schemaLocation，加载器也必须使用内置 XSD 校验。
        String file = "classpath:address-book/missing-version-without-schema-location.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("version"));
        }
    }

    @Test
    void loadsPrefixedDocumentUsingBundledSchemaOffline() throws Exception {
        // 使用命名空间前缀，声明的远端位置不可访问时仍使用 JAR 中的 XSD。
        String file = "classpath:address-book/prefixed-offline-schema.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 已登记的业务服务。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28001/example")), context.getUpdater().queryAllAddresses(service.getBindID()));
        }
    }

    @Test
    void rejectsBlankRegistrationAttribute() throws Exception {
        String file = "classpath:address-book/blank-version.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
        }
    }

    @Test
    void rejectsWrongNamespace() throws Exception {
        String file = "classpath:address-book/wrong-namespace.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
        }
    }

    @Test
    void rejectsUnknownServiceChild() throws Exception {
        String file = "classpath:address-book/unknown-service-child.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
        }
    }

    @Test
    void loadsBundledDtdByPublicIdOffline() throws Exception {
        // PUBLIC 标识映射到制品内 DTD，远端地址不可访问也不影响加载。
        String file = "classpath:address-book/dtd-public-offline.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 已注册的业务接口。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(2, context.getUpdater().queryAllAddresses(service.getBindID()).size());
        }
    }

    @Test
    void loadsBundledDtdBySystemId() throws Exception {
        String file = "classpath:address-book/dtd-system.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // SYSTEM 声明同样离线加载。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(List.of(new InterAddress("rsf://127.0.0.1:28001/example")), context.getUpdater().queryAllAddresses(service.getBindID()));
        }
    }

    @Test
    void dtdRejectsMissingVersion() throws Exception {
        String file = "classpath:address-book/dtd-missing-version.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("version"));
        }
    }

    @Test
    void dtdRejectsUnknownAttributeBeforeUpdatingAddressPool() throws Exception {
        String file = "classpath:address-book/dtd-unknown-attribute.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) // 校验整份文档再写入。
                    .group("billing").name("order-query").version("1.0").register();
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("unexpected"));
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
        }
    }

    @Test
    void dtdAllowsServiceWithoutStaticAddresses() throws Exception {
        String file = "classpath:address-book/dtd-empty-address-list.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(EchoService.class) // 仍然只更新已经存在的服务。
                    .group("billing").name("order-query").version("1.0").register();
            context.getUpdater().loadAddressBook(context.getClassLoader(), file);
            assertEquals(Set.of("rsf"), context.getUpdater().getSupportedProtocols("[billing]order-query-1.0"));
        }
    }

    @Test
    void rejectsUnknownDtdLocation() throws Exception {
        String file = "classpath:address-book/unknown-dtd.xml";
        try (MetContext context = new MetConfiguration().buildContext()) {
            IOException error = assertThrows(IOException.class, () -> context.getUpdater().loadAddressBook(context.getClassLoader(), file));
            assertTrue(error.getMessage().contains("Unsupported static address book DTD"));
        }
    }

    @Test
    void staticProviderListPreservesEveryAddressScheme() throws Exception {
        // 独立地址池未限定连接器能力时，XML 中不同协议的地址全部收录并可用。
        AddressPool pool = new AddressPool();
        String id = "[billing]order-query-1.0";
        pool.registerBucket(new AddressBucket(id, "local"));
        pool.loadAddressBook(getClass().getClassLoader(), "classpath:address-book/mixed-protocols.xml");
        assertEquals(4, pool.queryAllAddresses(id).size());
        assertEquals(pool.queryAllAddresses(id), pool.queryAvailableAddresses(id));
    }

    @Test
    void connectorSchemesFilterAvailabilityWithoutDiscardingStaticAddresses() throws Exception {
        // 同一份 XML 保留所有协议；只有连接器支持的两个 scheme 进入可用列表。
        AddressPool pool = new AddressPool();
        pool.configProtocols(Set.of("rsf", "jsonrpc"));
        String id = "[billing]order-query-1.0";
        pool.registerBucket(new AddressBucket(id, "local"));
        pool.loadAddressBook(getClass().getClassLoader(), "classpath:address-book/mixed-protocols.xml");
        assertEquals(4, pool.queryAllAddresses(id).size());
        assertEquals(3, pool.queryAvailableAddresses(id).size());
        assertTrue(pool.queryAvailableAddresses(id).stream() // 三个候选对应两个连接器支持的协议。
                .allMatch(address -> Set.of("rsf", "jsonrpc").contains(address.getSchema())));
        assertEquals(Set.of("rsf", "jsonrpc"), pool.getSupportedProtocols(id));
    }

    @Test
    void emptyXmlDoesNotResetConnectorSchemesOrDiscoveredAddresses() throws Exception {
        // XML 不携带协议范围；加载空列表不能重置连接器筛选或清除注册发现结果。
        AddressPool pool = new AddressPool();
        String id = "[billing]order-query-1.0";
        InterAddress rsf = new InterAddress("rsf", "127.0.0.1", 28001, "local");
        InterAddress jsonrpc = new InterAddress("jsonrpc", "127.0.0.2", 28002, "local");
        pool.appendAddress(id, List.of(rsf, jsonrpc));
        pool.configProtocols(Set.of("rsf"));
        assertEquals(rsf, pool.nextAddress(id, "echo", new Object[0]));
        pool.loadAddressBook(getClass().getClassLoader(), "classpath:address-book/empty-address-list.xml");
        assertEquals(Set.of("rsf"), pool.getSupportedProtocols(id));
        assertEquals(List.of(rsf), pool.queryAvailableAddresses(id));
        assertEquals(Set.of(rsf, jsonrpc), Set.copyOf(pool.queryAllAddresses(id)));
    }
}
