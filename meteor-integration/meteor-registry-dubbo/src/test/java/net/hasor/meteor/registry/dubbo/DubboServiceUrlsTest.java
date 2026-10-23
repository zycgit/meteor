/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hasor.example.registry.dubbo.RegistryEchoService;
import net.hasor.example.registry.dubbo.RegistryMethodCatalogue;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.annotation.MetIgnore;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.UrlUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实服务描述验证 Meteor 身份与原生 Dubbo URL 的转换，不连接网络。 */
public class DubboServiceUrlsTest {
    @Test
    public void providerMethodCatalogueUsesThePublishedProtocolName() throws Exception {
        // 方法的 Java 名称与公共远程名称不同。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(MappedMethods.class).register();
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "default");

            // 注册 URL 上报协议调用使用的名称，不上报原 Java 方法名。
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            List<String> methods = List.of(provider.getParameter("methods").split(","));
            assertTrue(methods.contains("renamed"));
            assertFalse(methods.contains("original"));
        }
    }

    @Test
    public void providerMethodCatalogueOmitsIgnoredInheritedMethods() throws Exception {
        // 被忽略的方法来自父接口，仍不得进入注册中心方法清单。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(MappedMethods.class).register();
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "default");

            // 清单与服务公共描述使用同一份方法筛选结果。
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertFalse(List.of(provider.getParameter("methods").split(",")).contains("hidden"));
        }
    }

    public interface IgnoredMethods {
        @MetIgnore
        String hidden();
    }

    public interface MappedMethods extends IgnoredMethods {
        @MetMethod(name = "renamed")
        String original(String value);
    }

    @Test
    public void unaliasedServiceUsesJavaInterfaceName() throws Exception {
        // 通过正式发布入口创建服务描述，未指定 Dubbo 别名。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            assertEquals(RegistryEchoService.class.getName(), DubboServiceUrls.serviceInterface(service));
        }
    }

    @Test
    public void dubboAliasReplacesTheWireInterfaceName() throws Exception {
        // 原生接口名称可由协议别名指定，不修改 Java 接口类型。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class) //
                    .aliasName("dubbo", "example.RemoteEcho") //
                    .register();
            assertEquals("example.RemoteEcho", DubboServiceUrls.serviceInterface(service));
        }
    }

    @Test
    public void blankAliasFallsBackToJavaInterfaceName() throws Exception {
        // 空白别名不成为注册中心的接口名称。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class) //
                    .aliasName("dubbo", " ") //
                    .register();
            assertEquals(RegistryEchoService.class.getName(), DubboServiceUrls.serviceInterface(service));
        }
    }

    @Test
    public void serviceWithoutProtocolRestrictionCanUseDubbo() throws Exception {
        // 未限制协议的服务允许注册到当前已挂载的 Dubbo 协议。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            assertFalse(DubboServiceUrls.selectedSchemes(context, service).isEmpty());
        }
    }

    @Test
    public void explicitlySelectedDubboServiceIsSupported() throws Exception {
        // 逻辑协议选择经 bootstrap 规范化后仍保留 Dubbo 能力。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).protocol("DUBBO").register();
            assertFalse(DubboServiceUrls.selectedSchemes(context, service).isEmpty());
        }
    }

    @Test
    public void serviceRestrictedToAnotherProtocolIsNotRegisteredAsDubbo() throws Exception {
        // 同一容器内的其他协议服务不应泄漏到原生 Dubbo 注册中心。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).protocol("rsf").register();
            assertTrue(DubboServiceUrls.selectedSchemes(context, service).isEmpty());
        }
    }

    @Test
    public void logicalDubboSelectionIncludesPlainAndSecureMounts() throws Exception {
        // 同一逻辑协议预先配置明文与安全挂载，构建上下文不打开监听。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("dubbos");
        try (MetContext context = configuration.buildContext()) {
            // 选择逻辑协议表示使用它的全部挂载，而不是偷偷只保留明文端点。
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).protocol("dubbo").register();
            assertEquals(Set.of("dubbo", "dubbos"), DubboServiceUrls.selectedSchemes(context, service));
        }
    }

    @Test
    public void secureAddressSchemeCannotSelectTheServiceProtocol() throws Exception {
        // 明文与安全挂载共用 Dubbo 协议名，安全地址使用独立前缀。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("dubbos");
        try (MetContext context = configuration.buildContext()) {
            // 服务协议选择只接受 SPI 名称，不把安全地址前缀解释成协议。
            var binding = context.publisher().rsfService(RegistryEchoService.class);
            assertThrows(IllegalArgumentException.class, () -> binding.protocol("dubbos").register());
        }
    }

    @Test
    public void customAddressSchemeCannotSelectTheServiceProtocol() throws Exception {
        // 自定义地址前缀只标识安全挂载的公开地址。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("native-secure");
        try (MetContext context = configuration.buildContext()) {
            // 未注册同名协议时，自定义前缀不能成为服务协议选择条件。
            var binding = context.publisher().rsfService(RegistryEchoService.class);
            assertThrows(IllegalArgumentException.class, () -> binding.protocol("native-secure").register());
        }
    }

    @Test
    public void providerCarriesItsServiceIdentityAndApplication() throws Exception {
        // 服务组、版本、接口别名和应用名称共同构成原生服务身份。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class, value -> value) //
                    .group("blue").version("1.2").aliasName("dubbo", "example.RemoteEcho").register();

            // URL 使用实际监听地址，身份参数与发布描述保持一致。
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "provider_zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertEquals("dubbo", provider.getProtocol());
            assertEquals("127.0.0.1", provider.getHost());
            assertEquals(20880, provider.getPort());
            assertEquals("example.RemoteEcho", provider.getServiceInterface());
            assertEquals("blue", provider.getParameter("group"));
            assertEquals("1.2", provider.getParameter("version"));
            assertEquals("url-mapping-test", provider.getParameter("application"));
            assertEquals("provider_zone", provider.getParameter("meteor.unit"));
        }
    }

    @Test
    public void providerAdvertisesEnabledDynamicHessian2Endpoint() throws Exception {
        // 桥接只声明插件实际提供的 Hessian2 编码，不发布其他编码器的能力。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertEquals("provider", provider.getParameter("side"));
            assertEquals("providers", provider.getParameter("category"));
            assertTrue(provider.getParameter("dynamic", false));
            assertTrue(provider.getParameter("enabled", false));
            assertEquals("hessian2", provider.getParameter("serialization"));
            assertEquals("hessian2", provider.getParameter("prefer.serialization"));
            assertFalse(provider.getParameter("ssl-enabled", true));
        }
    }

    @Test
    public void methodCatalogueIncludesInheritedMethodsAndDeduplicatesOverloads() throws Exception {
        // 方法目录有继承、同名重载和静态工具方法，注册信息只保留去重后的 RPC 方法名。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryMethodCatalogue> service = context.publisher() //
                    .rsfService(RegistryMethodCatalogue.class).register();
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertEquals("echo,ping", provider.getParameter("methods"));
        }
    }

    @Test
    public void tlsProviderKeepsDubboProtocolAndAdvertisesSsl() throws Exception {
        // TLS 是同一 Dubbo 线协议的安全传输，不成为另一套原生注册协议。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = new InterAddress("dubbos", "127.0.0.1", 20881, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertEquals("dubbo", provider.getProtocol());
            assertTrue(provider.getParameter("ssl-enabled", false));
        }
    }

    @Test
    public void secureSchemeComparisonIgnoresCase() throws Exception {
        // URI scheme 的大小写不影响 TLS 提供者声明。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = new InterAddress("dubbos", "127.0.0.1", 20881, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "DUBBOS");
            assertTrue(provider.getParameter("ssl-enabled", false));
        }
    }

    @Test
    public void publishHostOverridesWildcardListenerHost() throws Exception {
        // 对所有网卡监听时，用户显式给出远端可访问的发布地址。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = InterAddress.forBinding("dubbo", "0.0.0.0", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, "provider.example", "dubbos");
            assertEquals("provider.example", provider.getHost());
        }
    }

    @Test
    public void wildcardIpv4ListenerRequiresPublishHost() throws Exception {
        // 通配 IPv4 地址不能作为其他进程的调用目标。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = InterAddress.forBinding("dubbo", "0.0.0.0", 20880, "zone");
            assertThrows(IllegalArgumentException.class, //
                    () -> DubboServiceUrls.provider(context, service, address, null, "dubbos"));
        }
    }

    @Test
    public void wildcardIpv6ListenerRequiresPublishHost() throws Exception {
        // IPv6 通配监听地址同样不能直接发布。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = InterAddress.forBinding("dubbo", "::", 20880, "zone");
            assertThrows(IllegalArgumentException.class, //
                    () -> DubboServiceUrls.provider(context, service, address, null, "dubbos"));
        }
    }

    @Test
    public void wildcardIpv6PublishHostIsRejected() throws Exception {
        // 即使通过覆盖参数设置，也不能把通配 IPv6 地址发布出去。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            assertThrows(IllegalArgumentException.class, //
                    () -> DubboServiceUrls.provider(context, service, address, "::", "dubbos"));
        }
    }

    @Test
    public void blankPublishHostIsRejected() throws Exception {
        // 显式覆盖为空白时应报告配置错误，而不是偷偷回退监听地址。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            assertThrows(IllegalArgumentException.class, //
                    () -> DubboServiceUrls.provider(context, service, address, " ", "dubbos"));
        }
    }

    @Test
    public void consumerSubscribesProvidersWithItsExactIdentity() throws Exception {
        // 消费者订阅 providers 分类，并携带自己要引用的组与版本。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL subscription = DubboServiceUrls.consumer(context, service);
            assertEquals("consumer", subscription.getProtocol());
            assertEquals("consumer", subscription.getParameter("side"));
            assertEquals("providers", subscription.getParameter("category"));
            assertEquals("dubbo", subscription.getParameter("protocol"));
            assertEquals(RegistryEchoService.class.getName(), subscription.getServiceInterface());
            assertEquals("blue", subscription.getParameter("group"));
            assertEquals("1.2", subscription.getParameter("version"));
            assertEquals("hessian2", subscription.getParameter("serialization"));
            assertEquals("hessian2", subscription.getParameter("prefer.serialization"));
            assertFalse(subscription.getParameter("check", true));
        }
    }

    @Test
    public void consumerRegistrationUsesConsumersCategoryWithoutChangingSubscription() throws Exception {
        // 同一消费者的订阅与注册使用不同分类，不能互相修改不可变 URL。
        try (MetContext context = this.configuration().buildContext()) {
            URL subscription = DubboServiceUrls.consumer(context, this.consumer(context));
            URL registration = DubboServiceUrls.registration(subscription);
            assertEquals("consumers", registration.getParameter("category"));
            assertTrue(registration.getParameter("dynamic", false));
            assertEquals(subscription.getServiceInterface(), registration.getServiceInterface());
            assertEquals("providers", subscription.getParameter("category"));
        }
    }

    @Test
    public void providerNotificationMapsTheAdvertisedUnit() throws Exception {
        // 提供者发布的 Meteor 单元信息优先于消费者本地默认值。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL provider = this.provider().addParameter("meteor.unit", "remote_zone");
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, List.of(provider), "dubbos");
            assertEquals(List.of(new InterAddress("dubbo", "127.0.0.1", 20880, "remote_zone")), addresses);
        }
    }

    @Test
    public void nativeProviderWithoutMeteorUnitUsesConsumerDefault() throws Exception {
        // 原生 Dubbo 不发布 Meteor 单元信息时使用消费方已配置的默认单元。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            List<URL> notification = List.of(this.provider());
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            assertEquals(List.of(new InterAddress("dubbo", "127.0.0.1", 20880, "consumer_zone")), addresses);
        }
    }

    @Test
    public void sslProviderUsesConfiguredSecureMountScheme() throws Exception {
        // 自定义安全挂载与明文挂载同时在正式配置中声明。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("dubbo+tls");
        try (MetContext context = configuration.buildContext()) {
            // 注册中心上的 ssl-enabled 映射到消费方实际已配置的安全挂载。
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL provider = this.provider().addParameter("ssl-enabled", true);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, List.of(provider), "dubbo+tls");
            assertEquals("dubbo+tls", addresses.get(0).getSchema());
        }
    }

    @Test
    public void secureOnlyServiceIgnoresPlainProviderNotifications() throws Exception {
        // 消费方只配置安全挂载，服务仍按唯一的 Dubbo 协议名订阅。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("url-mapping-test");
        configuration.setBindAddress("127.0.0.1").setUnitName("consumer_zone");
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("dubbos");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).group("blue").version("1.2") //
                    .protocol("dubbo").register();
            URL plain = this.provider();
            URL secure = plain.setPort(20881).addParameter("ssl-enabled", true);

            // 同批通知中的明文地址不能成为安全服务的降级调用目标。
            List<URL> notification = List.of(plain, secure);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            InterAddress expected = new InterAddress("dubbos", "127.0.0.1", 20881, "consumer_zone");
            assertEquals(List.of(expected), addresses);
        }
    }

    @Test
    public void plainOnlyClientIgnoresSecureProviderNotifications() throws Exception {
        // 消费方只配置明文挂载，不能把安全提供者伪装成明文连接使用。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL plain = this.provider();
            URL secure = plain.setPort(20881).addParameter("ssl-enabled", true);

            // 仅已配置的明文提供者进入地址本，TLS 通知被排除。
            List<URL> notification = List.of(plain, secure);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            InterAddress expected = new InterAddress("dubbo", "127.0.0.1", 20880, "consumer_zone");
            assertEquals(List.of(expected), addresses);
        }
    }

    @Test
    public void logicalDubboServiceDiscoversBothConfiguredTransportModes() throws Exception {
        // 服务选择逻辑协议时允许使用当前已配置的明文和 TLS 挂载。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").option("tlsEnabled", true) //
                .protocol("dubbo").addressScheme("dubbos");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).group("blue").version("1.2") //
                    .protocol("dubbo").register();
            URL plain = this.provider();
            URL secure = plain.setPort(20881).addParameter("ssl-enabled", true);

            // 两种提供者各自使用对应地址 scheme，发现过程不丢弃其中一种。
            List<URL> notification = List.of(plain, secure);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            InterAddress plainAddress = new InterAddress("dubbo", "127.0.0.1", 20880, "consumer_zone");
            InterAddress secureAddress = new InterAddress("dubbos", "127.0.0.1", 20881, "consumer_zone");
            assertEquals(List.of(plainAddress, secureAddress), addresses);
        }
    }

    @Test
    public void duplicateProviderNotificationsProduceOneAddress() throws Exception {
        // 无关 URL 参数不同的同一地址不会重复加入地址池。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL first = this.provider();
            URL duplicate = first.addParameter("timestamp", "123456");
            List<URL> notification = List.of(first, duplicate);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            assertEquals(1, addresses.size());
        }
    }

    @Test
    public void distinctProviderAddressesRetainNotificationOrder() throws Exception {
        // 去重只消除相同地址，不重新排序独立提供者。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL first = this.provider().setPort(20881);
            URL second = this.provider();
            List<URL> notification = List.of(first, second);
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, notification, "dubbos");
            assertEquals(20881, addresses.get(0).getPort());
            assertEquals(20880, addresses.get(1).getPort());
        }
    }

    @Test
    public void disabledProviderIsRemovedFromDiscovery() throws Exception {
        // 原生 disabled=true 使提供者退出可调用地址集合。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL provider = this.provider().addParameter("disabled", true);
            assertTrue(DubboServiceUrls.providers(context, service, List.of(provider), "dubbos").isEmpty());
        }
    }

    @Test
    public void providerWithEnabledFalseIsRemovedFromDiscovery() throws Exception {
        // enabled=false 与 disabled=true 都表示该地址不可参与调用。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL provider = this.provider().addParameter("enabled", false);
            assertTrue(DubboServiceUrls.providers(context, service, List.of(provider), "dubbos").isEmpty());
        }
    }

    @Test
    public void otherWireProtocolIsNotAcceptedAsDubbo() throws Exception {
        // 同一服务的 Triple 地址不能交给 Dubbo 二进制会话。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL provider = this.provider().setProtocol("tri");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(provider), "dubbos").isEmpty());
        }
    }

    @Test
    public void emptyProviderMarkerClearsTheAddressSet() throws Exception {
        // 原生注册中心用 empty 协议通知该服务已无提供者。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL empty = this.provider().setProtocol("empty");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(empty), "dubbos").isEmpty());
        }
    }

    @Test
    public void nonProviderCategoryDoesNotBecomeAnAddress() throws Exception {
        // 路由或配置目录的 URL 不是实际提供者，即使协议和身份碰巧相同。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL router = this.provider().addParameter("category", "routers");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(router), "dubbos").isEmpty());
        }
    }

    @Test
    public void mismatchedInterfaceDoesNotBecomeAnAddress() throws Exception {
        // 不同接口不能因共用同一注册中心而混入地址本。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL other = this.provider().setPath("example.OtherService");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(other), "dubbos").isEmpty());
        }
    }

    @Test
    public void mismatchedGroupDoesNotBecomeAnAddress() throws Exception {
        // 同一接口不同分组的提供者保持隔离。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL other = this.provider().addParameter("group", "green");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(other), "dubbos").isEmpty());
        }
    }

    @Test
    public void mismatchedVersionDoesNotBecomeAnAddress() throws Exception {
        // 同一接口不同版本的提供者保持隔离。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL other = this.provider().addParameter("version", "2.0");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(other), "dubbos").isEmpty());
        }
    }

    @Test
    public void zeroProviderPortDoesNotBecomeAnAddress() throws Exception {
        // 未绑定实际端口的声明不能交给客户端建立连接。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL invalid = this.provider().setPort(0);
            assertTrue(DubboServiceUrls.providers(context, service, List.of(invalid), "dubbos").isEmpty());
        }
    }

    @Test
    public void oversizedProviderPortDoesNotBecomeAnAddress() throws Exception {
        // 通知中的端口超出 TCP 范围时过滤掉该提供者。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            URL invalid = this.provider().setPort(65536);
            assertTrue(DubboServiceUrls.providers(context, service, List.of(invalid), "dubbos").isEmpty());
        }
    }

    @Test
    public void absentGroupAndVersionMatchExplicitlyEmptyServiceIdentity() throws Exception {
        // 原生缺省身份没有 group/version 参数，Meteor 的空字符串描述与之匹配。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).group("").version("").register();
            URL provider = new URL("dubbo", "127.0.0.1", 20880, RegistryEchoService.class.getName());
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, List.of(provider), "dubbos");
            assertEquals(1, addresses.size());
        }
    }

    @Test
    public void aliasControlsProviderMatching() throws Exception {
        // 发现时匹配协议别名，不能只匹配本地 Java 类型名。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class) //
                    .group("blue").version("1.2").aliasName("dubbo", "example.RemoteEcho").register();
            URL provider = this.provider().setPath("example.RemoteEcho");
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, List.of(provider), "dubbos");
            assertEquals(1, addresses.size());
        }
    }

    @Test
    public void emptyNotificationContainsNoProviders() throws Exception {
        // 无提供者通知不虚构一个本机调用地址。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            assertTrue(DubboServiceUrls.providers(context, service, List.of(), "dubbos").isEmpty());
        }
    }

    @Test
    public void consumerRetainsLiteralMeteorDefaultIdentityWithoutNativeOverride() throws Exception {
        // 普通 binding 的 default/1.0.0 是字面服务身份，不推测它等同于原生空身份。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).register();
            URL consumer = DubboServiceUrls.consumer(context, service);
            assertEquals("default", consumer.getParameter("group"));
            assertEquals("1.0.0", consumer.getParameter("version"));
        }
    }

    @Test
    public void providerRetainsLiteralMeteorDefaultIdentityWithoutNativeOverride() throws Exception {
        // 普通发布不隐式改写服务分组或版本，否则同名原生服务会发生身份混淆。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class, value -> value).register();
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertEquals("default", provider.getParameter("group"));
            assertEquals("1.0.0", provider.getParameter("version"));
        }
    }

    @Test
    public void nativeGroupMetadataCanSelectAnEmptyGroup() throws Exception {
        // 原生空分组通过明确元信息表达，不修改内部 binding 的 blue 分组。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            URL consumer = DubboServiceUrls.consumer(context, service);
            assertNull(consumer.getParameters().get("group"));
            assertEquals("1.2", consumer.getParameter("version"));
            assertEquals("blue", service.getBindGroup());
        }
    }

    @Test
    public void nativeVersionMetadataCanSelectAnEmptyVersion() throws Exception {
        // 原生空版本通过明确元信息表达，不修改内部 binding 的 1.2 版本。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.version", "");
            URL consumer = DubboServiceUrls.consumer(context, service);
            assertEquals("blue", consumer.getParameter("group"));
            assertNull(consumer.getParameters().get("version"));
            assertEquals("1.2", service.getBindVersion());
        }
    }

    @Test
    public void providerUsesItsExplicitNativeEmptyIdentity() throws Exception {
        // 同一个服务可以保留内部身份，并显式发布为原生无组无版本接口。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            assertNull(provider.getParameters().get("group"));
            assertNull(provider.getParameters().get("version"));
            assertEquals("blue", service.getBindGroup());
            assertEquals("1.2", service.getBindVersion());
        }
    }

    @Test
    public void explicitNativeIdentityOverridesNonemptyGroupAndVersion() throws Exception {
        // 元信息覆盖不仅支持空字符串，也可选择与内部身份不同的原生组与版本。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "native-group");
            service.setMetaData("dubbo.registry.version", "native-version");
            URL consumer = DubboServiceUrls.consumer(context, service);
            assertEquals("native-group", consumer.getParameter("group"));
            assertEquals("native-version", consumer.getParameter("version"));
        }
    }

    @Test
    public void providerMatchingUsesExplicitNativeEmptyIdentity() throws Exception {
        // 原生默认服务的发现依据覆盖后的空身份，不错误套用内部 blue/1.2。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            URL provider = new URL("dubbo", "127.0.0.1", 20880, RegistryEchoService.class.getName());
            List<InterAddress> addresses = DubboServiceUrls.providers(context, service, List.of(provider), "dubbos");
            assertEquals(1, addresses.size());
        }
    }

    @Test
    public void literalMeteorDefaultIdentityDoesNotMatchANativeEmptyIdentity() throws Exception {
        // 没有显式覆盖时，default/1.0.0 服务不能引用无分组无版本的另一套原生服务。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = context.publisher() //
                    .rsfService(RegistryEchoService.class).register();
            URL provider = new URL("dubbo", "127.0.0.1", 20880, RegistryEchoService.class.getName());
            assertTrue(DubboServiceUrls.providers(context, service, List.of(provider), "dubbos").isEmpty());
        }
    }

    @Test
    public void nativeEmptyIdentityDoesNotDiscoverTheInternalLiteralIdentity() throws Exception {
        // 覆盖为空身份后不再接受内部同名服务，避免两个原生服务被合并。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            assertTrue(DubboServiceUrls.providers(context, service, List.of(this.provider()), "dubbos").isEmpty());
        }
    }

    @Test
    public void nativeConsumerMatchesProviderWithExplicitEmptyNativeIdentity() throws Exception {
        // 原生未配置分组与版本时，URL 不包含这两个键。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            InterAddress address = new InterAddress("dubbo", "127.0.0.1", 20880, "zone");
            URL provider = DubboServiceUrls.provider(context, service, address, null, "dubbos");
            URL consumer = new URL("consumer", "127.0.0.1", 0, RegistryEchoService.class.getName());

            // 使用 SDK 真正的发现匹配规则证明本机发布可被原生消费者发现。
            assertNull(provider.getParameters().get("group"));
            assertNull(provider.getParameters().get("version"));
            assertTrue(UrlUtils.isMatch(consumer, provider));
        }
    }

    @Test
    public void consumerWithExplicitEmptyNativeIdentityMatchesNativeProvider() throws Exception {
        // 原生默认服务的提供者 URL 没有 group/version 参数。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<RegistryEchoService> service = this.consumer(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            URL consumer = DubboServiceUrls.consumer(context, service);
            URL provider = new URL("dubbo", "127.0.0.1", 20880, RegistryEchoService.class.getName());

            // 空覆盖在注册中心省略参数，才能通过 SDK 的 null 身份匹配。
            assertNull(consumer.getParameters().get("group"));
            assertNull(consumer.getParameters().get("version"));
            assertTrue(UrlUtils.isMatch(consumer, provider));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setApplicationName("url-mapping-test");
        configuration.setBindAddress("127.0.0.1").setUnitName("consumer_zone");
        configuration.connector("tcp").protocol("dubbo");
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }

    private MetBindInfo<RegistryEchoService> consumer(MetContext context) {
        return context.publisher().rsfService(RegistryEchoService.class).group("blue").version("1.2").register();
    }

    private URL provider() {
        Map<String, String> parameters = Map.of("group", "blue", "version", "1.2", "category", "providers");
        return new URL("dubbo", "127.0.0.1", 20880, RegistryEchoService.class.getName(), parameters);
    }
}
