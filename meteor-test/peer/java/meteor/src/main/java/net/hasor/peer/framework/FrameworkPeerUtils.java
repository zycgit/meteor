/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderQuery;
import net.hasor.example.integration.FrameworkOrderService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;

/** 进程控制文件和测试输入；框架装配仍由各自 Main 完成。 */
public final class FrameworkPeerUtils {
    public static final String SERVICE_PACKAGE = "net.hasor.example.integration";

    private FrameworkPeerUtils() {
    }

    public static Properties settings(String file) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(file))) {
            properties.load(input);
        }
        return properties;
    }

    public static DataSource database(Properties properties) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(properties.getProperty("test.jdbc.url"));
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    public static DubboRegistryConfiguration dubbo(Properties properties) {
        DubboRegistryConfiguration registry = new DubboRegistryConfiguration();
        registry.setAddress(properties.getProperty("test.dubbo.address"));
        registry.setPublishHost(properties.getProperty("test.publish-host"));
        registry.setParameters(Map.of("group", properties.getProperty("test.dubbo.group")));
        return registry;
    }

    public static void ready(MetContext context, Path output) throws Exception {
        MetServiceType role = context.getServiceInfo(FrameworkOrderService.class).getServiceType();
        if (role != MetServiceType.Provider) {
            throw new IllegalStateException("Framework did not publish the provider Bean");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("role", role.name());
        report.put("application", context.getApplicationName());
        if (nxn()) {
            InterAddress listen = context.getListenAddress(context.getDefaultProtocol());
            String host = System.getenv("METEOR_TEST_HOST_JAVA");
            if (StringUtils.isBlank(host)) {
                throw new IllegalStateException("METEOR_TEST_HOST_JAVA is required for framework provider readiness");
            }
            InterAddress reachable = new InterAddress(listen.getSchema(), host, listen.getPort(), listen.getFormUnit());
            report.put("event", "ready");
            report.put("target", reachable.toHostSchema());
        }
        write(output, report);
    }

    public static void invoke(MetContext context, FrameworkOrderService orders, Path output) throws Exception {
        var service = context.getServiceInfo(FrameworkOrderService.class);
        if (service.getServiceType() != MetServiceType.Consumer) {
            throw new IllegalStateException("Framework did not inject a consumer proxy");
        }

        // 只观察自动发现结果，不查询存储后把提供者地址塞给消费者。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses != null && !addresses.isEmpty()) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new TimeoutException("No automatically discovered framework provider: " + service.getBindID());
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }

        // 真实 RPC 保存、查询同一笔订单；JUnit 核对服务端身份、Unicode 与二进制内容。
        Order input;
        if (nxn()) {
            Path request = Path.of(System.getenv("METEOR_NXN_REQUEST_FILE"));
            input = new ObjectMapper().readValue(request.toFile(), Order.class);
        } else {
            input = new Order();
            input.setId(731);
            input.setDescription("跨框架订单");
            input.setAttachment(new byte[] { 0, 1, 127, -1 });
        }
        Order saved = orders.save(input);
        OrderQuery query = new OrderQuery();
        query.setId(input.getId());
        Order result = orders.find(query);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("role", service.getServiceType().name());
        report.put("application", context.getApplicationName());
        if (nxn()) {
            report.put("event", "result");
            report.put("order", result);
            report.put("saved", saved);
        } else {
            report.put("id", result.getId());
            report.put("description", result.getDescription());
            report.put("attachment", Base64.getEncoder().encodeToString(result.getAttachment()));
        }
        write(output, report);
    }

    private static boolean nxn() {
        return StringUtils.isNotBlank(System.getenv("METEOR_NXN_REQUEST_FILE"));
    }

    private static void write(Path output, Map<String, ?> value) throws Exception {
        Path pending = output.resolveSibling(output.getFileName() + ".pending");
        new ObjectMapper().writeValue(pending.toFile(), value);
        Files.move(pending, output, StandardCopyOption.ATOMIC_MOVE);
        if (nxn()) {
            System.out.println(new ObjectMapper().writeValueAsString(value));
            System.out.flush();
        }
    }
}
