/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方浏览器消费者通过显式存储适配发现 Meteor，Java 不代发业务请求。 */
@ParameterizedClass(autoCloseArguments = false, name = "grpc-web/javascript-browser/adapter/{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "grpc-web")
public class GrpcWebRegistryAdapterScenarioTest {
    public static Collection<Object[]> backends() {
        return List.of(new Object[] { "jdbc" }, new Object[] { "zookeeper" }, new Object[] { "nacos" });
    }

    @TempDir
    public Path data;

    private final String backend;

    public GrpcWebRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialBrowserDiscoversBinaryUnaryEndpoint() throws Exception {
        // 浏览器先创建真实独立 origin，Meteor 通过 bootstrap 挂载协议并允许该来源。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (GrpcWebBrowserProcess browser = GrpcWebBrowserProcess.start(); //
             RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            String origin = browser.awaitOrigin();
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, origin);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                GrpcProtoResource proto = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/grpc/web.proto"));
                MetBindInfo<GrpcWebService> service = provider.publisher() //
                        .rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .name("browser-echo").aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource("grpc-web", proto).register();

                // 仅按服务 ID 订阅存储地址；完整 URL 由注册端点提供，没有读取本地监听器。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "grpc-web")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals("http", endpoint.getScheme());
                    assertEquals("/web", endpoint.getPath());
                    JsonNode result = browser.invoke(endpoint.toString(), "binary");

                    // Chromium 中的官方生成客户端完成 Protobuf 与 HTTP，结果证明跨域预检及业务成功。
                    assertEquals("中文🙂 <&>", result.path("value").textValue());
                    assertEquals(7, result.path("count").intValue());
                    assertTrue(this.hasPreflight(result));
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialBrowserDiscoversTextUnaryEndpoint() throws Exception {
        // 文本模式使用独立浏览器与独立注册库，继续复用原有官方 grpc-web 客户端脚本。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (GrpcWebBrowserProcess browser = GrpcWebBrowserProcess.start(); //
             RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            String origin = browser.awaitOrigin();
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, origin);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                GrpcProtoResource proto = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/grpc/web.proto"));
                MetBindInfo<GrpcWebService> service = provider.publisher() //
                        .rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .name("browser-echo").aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource("grpc-web", proto).register();

                // 发现适配只交付服务完整 URL；base64 封帧、请求与响应都在浏览器中处理。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "grpc-web")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals("http", endpoint.getScheme());
                    assertEquals("/web", endpoint.getPath());
                    JsonNode result = browser.invoke(endpoint.toString(), "text");

                    // 模式选择不能影响 Unicode 或数字，真实浏览器仍须通过 CORS 预检。
                    assertEquals("中文🙂 <&>", result.path("value").textValue());
                    assertEquals(7, result.path("count").intValue());
                    assertTrue(this.hasPreflight(result));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String origin) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
        configuration.setApplicationName("meteor-grpc-web-provider");
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展的内部管理出口，不监听。
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("grpc-web").addressScheme("http").contextPath("/web") //
                .option("streamBody", true).option("corsOrigins", origin) //
                .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
        return configuration;
    }

    private boolean hasPreflight(JsonNode result) {
        for (JsonNode request : result.path("requests")) {
            if (StringUtils.equals("OPTIONS", request.path("method").textValue())) {
                return true;
            }
        }
        return false;
    }
}
