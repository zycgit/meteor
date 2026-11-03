/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.peer.application;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.contract.complex.ComplexDocument;
import net.hasor.example.contract.complex.ComplexIndex;
import net.hasor.example.contract.complex.ComplexValueService;
import net.hasor.example.contract.complex.ComplexValueServiceImpl;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 标准基础类型与复杂结构的独立进程，业务调用始终使用已有的具体 Java 契约。 */
public final class StandardTypeApplicationMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private StandardTypeApplicationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String api = arguments[0];
        String protocol = arguments[1];
        boolean provider = StringUtils.equals("Provider", arguments[2]);
        if (!List.of("scalar-v1", "complex-v1").contains(api)) {
            throw new IllegalArgumentException("Unknown standard type API: " + api);
        }
        Path requestFile = Path.of(System.getenv("METEOR_NXN_REQUEST_FILE"));
        JsonNode request = JSON.readTree(requestFile.toFile());
        MetConfiguration configuration = configuration(protocol, provider, requestFile);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<?> binding;
            if (StringUtils.equals("scalar-v1", api)) {
                ConfigurationBuilder<TypeEchoService> declaration;
                if (provider) {
                    declaration = context.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl());
                } else {
                    declaration = context.publisher().rsfService(TypeEchoService.class);
                }
                binding = configure(declaration, protocol, "scalarValues", provider).register();
            } else {
                ConfigurationBuilder<ComplexValueService> declaration;
                if (provider) {
                    ComplexValueServiceImpl implementation = new ComplexValueServiceImpl();
                    declaration = context.publisher().rsfService(ComplexValueService.class, implementation);
                } else {
                    declaration = context.publisher().rsfService(ComplexValueService.class);
                }
                binding = configure(declaration, protocol, "complexValues", provider).register();
            }

            if (provider) {
                // 只有监听就绪后才报告可达地址；生命周期继续由该进程持有的上下文管理。
                InterAddress address = context.getListenAddress(protocol);
                String host = environment("METEOR_TEST_PEER_HOST", "127.0.0.1");
                String scheme = "http";
                String path = "/standard";
                if (StringUtils.equals("rsf", protocol)) {
                    scheme = protocol;
                    path = "/" + address.getFormUnit();
                } else if (StringUtils.equals("hessian", protocol)) {
                    String service = "complexValues";
                    if (StringUtils.equals("scalar-v1", api)) {
                        service = "scalarValues";
                    }
                    path += "/" + service;
                }
                URI target = new URI(scheme, null, host, address.getPort(), path, null, null);
                OrderExampleUtils.ready(target.toString(), "Standard type provider is ready: ");
                OrderExampleUtils.awaitShutdown(context);
            } else {
                // 出站地址只来自提供者的就绪事件，代理没有本地实现可以绕过真实网络。
                URI target = URI.create(System.getenv("METEOR_ORDER_TARGET"));
                InterAddress address = new InterAddress(protocol, target.getHost(), target.getPort(), "nxn_types");
                Object remote = context.getRsfClient(address).getRemote(binding);
                if (StringUtils.equals("scalar-v1", api)) {
                    TypeValue value = JSON.treeToValue(request, TypeValue.class);
                    TypeValue response = ((TypeEchoService) remote).echo(value);
                    result(response);
                } else if (request.has("document")) {
                    ComplexDocument value = JSON.treeToValue(request.required("document"), ComplexDocument.class);
                    ComplexDocument response = ((ComplexValueService) remote).acceptDocument(value);
                    result(response);
                } else {
                    ComplexIndex value = JSON.treeToValue(request.required("index"), ComplexIndex.class);
                    ComplexIndex response = ((ComplexValueService) remote).acceptIndex(value);
                    result(response);
                }
            }
        }
    }

    private static MetConfiguration configuration(String protocol, boolean provider, Path request) throws IOException {
        if (!List.of("rsf", "jsonrpc", "hessian").contains(protocol)) {
            throw new IllegalArgumentException("Unknown standard type protocol: " + protocol);
        }
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("nxn_types").setDefaultProtocol(protocol).setDefaultGroup("nxn-types");
        String role = "consumer";
        if (provider) {
            role = "provider";
        }
        configuration.setApplicationName("standard-types-" + role);
        Path data = Files.createDirectories(request.getParent().resolve(role + "-meteor-data"));
        configuration.setDataHome(data);
        String transport = "http";
        if (StringUtils.equals("rsf", protocol)) {
            transport = "tcp";
        }
        var connector = configuration.connector(transport);
        if (provider) {
            connector.bind(environment("METEOR_ORDER_BIND_HOST", "127.0.0.1"), 0);
        }
        var mount = connector.protocol(protocol);
        if (!StringUtils.equals("rsf", protocol)) {
            mount.contextPath("/standard");
        }
        if (StringUtils.equals("hessian", protocol)) {
            mount.option("version", 2);
        }
        return configuration;
    }

    private static <T> ConfigurationBuilder<T> configure(ConfigurationBuilder<T> declaration, String protocol,
            String service, boolean provider) {
        declaration.name("meteor.standard." + service).version("1").protocol(protocol).timeout(5000);
        if (StringUtils.equals("hessian", protocol)) {
            declaration.serialize("Hessian");
            if (!provider) {
                URI target = URI.create(System.getenv("METEOR_ORDER_TARGET"));
                declaration.contextPathByExact(protocol, target.getPath());
            }
        } else {
            declaration.serialize("Json");
        }
        if (!StringUtils.equals("rsf", protocol)) {
            declaration.aliasName(protocol, service);
        }
        return declaration;
    }

    private static void result(Object response) throws Exception {
        System.out.println(JSON.writeValueAsString(Map.of("event", "result", "value", response)));
        System.out.flush();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        return value;
    }
}
