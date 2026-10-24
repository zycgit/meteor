/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.container;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Properties;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.discovery.AddressSelectionAwaiter;
import org.h2.jdbcx.JdbcDataSource;

/** 独立应用容器的公开入口；文件仅交换控制与断言数据，订单调用走真实 RSF 网络。 */
public final class MeteorOrderContainerMain {
    private static final String SERVICE_NAME = "meteor.container.orders";

    private MeteorOrderContainerMain() {
    }

    public static void main(String[] args) throws Exception {
        // 独立应用显式使用 Cobble 的 JDK 日志，避免 SDK 类路径中的空日志绑定吞掉同步异常。
        LoggerFactory.useJdkLogger();
        String role = args[0];
        Path home = Path.of(args[1]);
        Properties settings = read(home.resolve("settings.properties"));
        if (StringUtils.equals("provider", role)) {
            provider(home, settings);
        } else if (StringUtils.equals("consumer", role)) {
            consumer(home, settings, args.length > 2 ? args[2] : null);
        } else {
            throw new IllegalArgumentException("Unknown container application role: " + role);
        }
    }

    private static void provider(Path home, Properties settings) throws Exception {
        // 容器拥有自身上下文，实际监听地址可从同一 Docker 网络内访问并登记到注册中心。
        String host = InetAddress.getLocalHost().getHostAddress();
        MetConfiguration configuration = configuration(home, settings, "provider");
        configuration.connector("tcp").bind(host, 0).protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = context.publisher() //
                    .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE_NAME).protocol("rsf").register();

            // 发布后才报告就绪，输入流关闭时由应用退出并释放自己创建的上下文。
            Properties ready = new Properties();
            ready.setProperty("address", context.getListenAddress("rsf").toHostSchema());
            ready.setProperty("hostname", InetAddress.getLocalHost().getHostName());
            ready.setProperty("instanceId", context.getInstanceID());
            ready.setProperty("serviceId", service.getBindID());
            ready.setProperty("unit", context.getSettings().getUnitName());
            write(home.resolve("ready.properties"), ready);
            while (System.in.read() >= 0) {
                // 父测试拥有进程，关闭其标准输入即结束提供者生命周期。
            }
        }
    }

    private static void consumer(Path home, Properties settings, String directAddress) throws Exception {
        // 消费者只启用出站能力；注册发现场景没有预置服务地址。
        MetConfiguration configuration = configuration(home, settings, "consumer");
        configuration.connector("tcp").protocol("rsf");
        Properties result = new Properties();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = context.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE_NAME).protocol("rsf").register();
            OrderDemoService orders;
            if (directAddress == null) {
                // 失败时只记录公开快照，不主动拉取、补地址或重新发起业务调用。
                try {
                    AddressSelectionAwaiter.await(context, service, "save");
                } catch (Exception failure) {
                    Properties diagnostics = new Properties();
                    diagnostics.setProperty("instanceId", context.getInstanceID());
                    diagnostics.setProperty("serviceId", service.getBindID());
                    diagnostics.setProperty("unit", context.getSettings().getUnitName());
                    var addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
                    diagnostics.setProperty("addresses", addresses.toString());
                    write(home.resolve("consumer-discovery-failure.properties"), diagnostics);
                    throw failure;
                }
                InterAddress discovered = context.getUpdater().queryAvailableAddresses(service.getBindID()).get(0);
                result.setProperty("target", discovered.toHostSchema());
                orders = context.getRsfClient().getRemote(service);
            } else {
                result.setProperty("target", directAddress);
                orders = context.getRsfClient(directAddress).getRemote(service);
            }

            // 复用示例 DTO 执行保存与读取，消费者不含本地实现，无法走本地调用捷径。
            Order expected = new Order();
            expected.setId(Integer.parseInt(settings.getProperty("order.id")));
            expected.setDescription(settings.getProperty("order.description"));
            expected.setAttachment(Base64.getDecoder().decode(settings.getProperty("order.attachment")));
            Order saved = orders.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = orders.find(query);
            result.setProperty("saved.id", Integer.toString(saved.getId()));
            result.setProperty("found.id", Integer.toString(found.getId()));
            result.setProperty("found.description", found.getDescription());
            result.setProperty("found.attachment", Base64.getEncoder().encodeToString(found.getAttachment()));
            result.setProperty("hostname", InetAddress.getLocalHost().getHostName());
            result.setProperty("listening", Boolean.toString(context.getListenAddress("rsf") != null));
        }

        // 完整释放消费者上下文后才交付结果，测试同时观察进程退出码。
        write(home.resolve("result.properties"), result);
    }

    private static MetConfiguration configuration(Path home, Properties settings, String role) {
        MetConfiguration configuration = new MetConfiguration().setUnitName("container");
        configuration.setApplicationName("container-order-" + role);
        configuration.setDataHome(home.resolve(role + "-data"));
        String backend = settings.getProperty("backend");
        if (!StringUtils.equals("direct", backend)) {
            configuration.addExtension(registry(settings));
        }
        return configuration;
    }

    private static RegistryConfiguration registry(Properties settings) {
        // 同一份端点描述分别构建独立存储会话，不向其他容器借用 Java 对象。
        String backend = settings.getProperty("backend");
        return switch (backend) {
            case "jdbc" -> {
                JdbcDataSource source = new JdbcDataSource();
                source.setURL(settings.getProperty("jdbcUrl"));
                source.setUser(settings.getProperty("user"));
                source.setPassword(settings.getProperty("password"));
                yield new JdbcRegistryConfiguration().setDataSource(source);
            }
            case "zookeeper" -> new ZookeeperRegistryConfiguration() //
                    .setAddress(settings.getProperty("address")).setRoot(settings.getProperty("root"));
            case "nacos" -> new NacosRegistryConfiguration() //
                    .setAddress(settings.getProperty("address")).setGroup(settings.getProperty("group"));
            default -> throw new IllegalArgumentException("Unknown registry backend: " + backend);
        };
    }

    private static Properties read(Path file) throws Exception {
        Properties result = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    private static void write(Path file, Properties values) throws Exception {
        // 通过同目录原子替换发出完成信号，读取方不会读到半份状态文件。
        Path pending = file.resolveSibling(file.getFileName() + ".pending");
        try (Writer writer = Files.newBufferedWriter(pending, StandardCharsets.UTF_8)) {
            values.store(writer, null);
        }
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE);
    }
}
