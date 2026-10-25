/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.alibaba.nacos.api.config.model.ConfigBasicInfo;
import com.alibaba.nacos.api.model.Page;
import com.alibaba.nacos.api.naming.pojo.maintainer.ServiceView;
import com.alibaba.nacos.maintainer.client.config.ConfigMaintainerFactory;
import com.alibaba.nacos.maintainer.client.config.ConfigMaintainerService;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerFactory;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

/** 借用 Compose 的 Nacos，每个场景句柄只创建和清理自己的分组。 */
public final class NacosTestServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ACTIVE_GROUPS = ConcurrentHashMap.newKeySet();
    private static Path sharedHome;
    private static String sharedAddress;
    private static boolean cleanupFailed;
    private static ConfigMaintainerService sharedConfigs;
    private static NamingMaintainerService sharedNaming;

    private final Path home;
    private final String prefix = "meteor-test-" + UUID.randomUUID();
    private final Map<String, String> groups = new LinkedHashMap<>();

    public NacosTestServer(Path workDirectory) throws IOException {
        this.home = Files.createDirectories(workDirectory);
    }

    public void start() throws Exception {
        // 场景借用 worker 服务，地址和分组记录属于当前场景的日志目录。
        startShared();
        Files.writeString(this.home.resolve("registry-address.txt"), this.address());
    }

    private static synchronized void startShared() throws Exception {
        if (sharedConfigs != null) {
            return;
        }

        // Compose 管理服务生命周期；当前 JVM 只拥有维护客户端和清理记录。
        String address = System.getenv("METEOR_TEST_NACOS_ADDRESS");
        if (StringUtils.isBlank(address)) {
            throw new IllegalStateException("METEOR_TEST_NACOS_ADDRESS is required");
        }
        URI endpoint = URI.create("http://" + address);
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        if (host == null || port <= 0) {
            throw new IllegalArgumentException("Nacos address must contain host and port: " + address);
        }
        if (!ready(address) || !listening(host, port + 1000)) {
            throw new IOException("Compose Nacos service is not ready: " + address);
        }

        String directory = System.getProperty("meteor.test.container.workDirectory", "build/container-scenarios");
        Path home = Files.createDirectories(Path.of(directory).resolve("registry-services/nacos"));
        ConfigMaintainerService configs = ConfigMaintainerFactory.createConfigMaintainerService(address);
        NamingMaintainerService naming = null;
        try {
            naming = NamingMaintainerFactory.createNamingMaintainerService(address);
            Files.writeString(home.resolve("lifecycle.properties"), "state=borrowed\naddress=" + address + "\n");
            sharedHome = home;
            Runtime.getRuntime().addShutdownHook(new Thread(NacosTestServer::releaseShared, "nacos-client-close"));
            sharedNaming = naming;
            sharedAddress = address;
            sharedConfigs = configs;
        } catch (Exception failure) {
            try {
                configs.shutdown();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (naming != null) {
                try {
                    naming.shutdown();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    public String address() {
        return sharedAddress;
    }

    public String group() {
        return this.group("default");
    }

    public String group(String scope) {
        // 同一场景的提供者和消费者复用分组，不同句柄即使 scope 相同也隔离。
        return this.groups.computeIfAbsent(scope, key -> {
            String group = this.prefix + "-" + key;
            ACTIVE_GROUPS.add(group);
            return group;
        });
    }

    public void release(String scope) throws Exception {
        String group = this.groups.get(scope);
        if (group == null) {
            return;
        }

        // Naming 范围先等待本场景的应用退出，避免清理仍然活跃的注册数据。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<ServiceView> services = services(group);
        while (hasInstances(services) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
            services = services(group);
        }
        if (hasInstances(services)) {
            throw new IOException("Nacos scope still has registered instances: " + group);
        }
        for (ServiceView service : services) {
            sharedNaming.removeService("public", group, service.getName());
        }
        if (!services(group).isEmpty()) {
            throw new IOException("Nacos Naming scope was not removed: " + group);
        }

        // 正式维护 API 只删除当前 group 的持久配置，其他场景的数据不参与查询。
        List<Long> ids = new ArrayList<>();
        Page<ConfigBasicInfo> page;
        int number = 1;
        do {
            page = sharedConfigs.listConfigs("", group, "public", null, null, null, number++, 100);
            for (ConfigBasicInfo config : page.getPageItems()) {
                ids.add(config.getId());
            }
        } while (number <= page.getPagesAvailable());
        if (!ids.isEmpty() && !sharedConfigs.deleteConfigs(ids)) {
            throw new IOException("Nacos Config scope was not removed: " + group);
        }
        if (sharedConfigs.listConfigs("", group, "public").getTotalCount() != 0) {
            throw new IOException("Nacos Config scope remains after deletion: " + group);
        }
        this.groups.remove(scope);
        ACTIVE_GROUPS.remove(group);
    }

    private static List<ServiceView> services(String group) throws Exception {
        List<ServiceView> result = new ArrayList<>();
        Page<ServiceView> page;
        int number = 1;
        do {
            page = sharedNaming.listServices("public", group, "", false, number++, 100);
            for (ServiceView service : page.getPageItems()) {
                // Nacos 按模式筛选，清理必须再次精确核对所属 group。
                if (StringUtils.equals(group, service.getGroupName())) {
                    result.add(service);
                }
            }
        } while (number <= page.getPagesAvailable());
        return result;
    }

    private static boolean hasInstances(List<ServiceView> services) {
        for (ServiceView service : services) {
            if (service.getIpCount() > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (String scope : new ArrayList<>(this.groups.keySet())) {
            try {
                this.release(scope);
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            recordCleanup(failure);
            throw failure;
        }
    }

    private static void releaseShared() {
        Exception failure = null;
        if (!ACTIVE_GROUPS.isEmpty()) {
            failure = new IOException("Nacos scopes were not released: " + ACTIVE_GROUPS);
        }
        try {
            sharedConfigs.shutdown();
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        try {
            sharedNaming.shutdown();
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        recordCleanup(failure);
        if (failure != null) {
            throw new IllegalStateException("Cannot release Nacos clients", failure);
        }
    }

    private static boolean listening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 200);
            return true;
        } catch (IOException notReady) {
            return false;
        }
    }

    private static boolean ready(String address) {
        URI endpoint = URI.create("http://" + address + "/nacos/v3/admin/core/state/readiness");
        HttpURLConnection connection = null;
        try {
            // 健康检查直连本轮服务，不继承宿主代理或只以端口握手代替就绪。
            connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            try (var input = connection.getInputStream()) {
                JsonNode response = JSON.readTree(input);
                if (response == null) {
                    return false;
                }
                boolean successful = response.path("code").asInt(-1) == 0;
                return successful && StringUtils.equals("ok", response.path("data").asText());
            }
        } catch (IOException notReady) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static synchronized void recordCleanup(Exception failure) {
        // 聚合器在 worker 退出后核对状态，不能把 shutdown hook 的异常当作通过。
        try {
            cleanupFailed |= failure != null;
            String state = "state=released\n";
            if (cleanupFailed) {
                state = "state=failed\n";
            }
            if (failure != null) {
                try (PrintWriter error = new PrintWriter( //
                        Files.newBufferedWriter(sharedHome.resolve("cleanup-error.log")))) {
                    failure.printStackTrace(error);
                }
            }
            Files.writeString(sharedHome.resolve("lifecycle.properties"), state);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Cannot record registry service cleanup", unavailable);
        }
    }
}
