/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.config.Bean;
import net.hasor.config.Configuration;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.rpc.RpcServer;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.global.http.ConsoleApiFilter;
import net.hasor.meteor.console.global.http.ConsoleJsonRender;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.web.WebApiBinder;
import net.hasor.web.WebModule;

/** 统一启动入口；HTTP 监听由 Hasor Boot 原生配置控制。 */
@Configuration
public final class ConsoleMain implements WebModule {
    /** 内置默认配置先加载，外部 meteor.properties 覆盖同名项。 */
    public static void main(String[] arguments) throws Exception {
        LoggerFactory.useJdkLogger();
        String config = configuration().toUri().toString();
        Boot boot = new Boot().arguments(arguments).sources(ConsoleMain.class).hconfigFile(config);
        try (BootApplication application = boot.start()) {
            application.join();
        }
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public MetContext meteorContext(MeteorConfig config) throws IOException {
        // 仅为直连管理提供 RPC 能力，不连接注册中心。
        MetConfiguration meteor = new MetConfiguration();
        meteor.setApplicationName("meteor-console");
        meteor.setUnitName(config.getUnit());
        meteor.setDefaultTimeout(5000);
        meteor.setConnectTimeout(3000);
        meteor.connector("tcp").protocol("rsf");
        meteor.addExtension(new RegistryConfiguration().setOnline(false));
        return meteor.buildContext();
    }

    @Override
    public void loadModule(WebApiBinder binder) {
        binder.bindType(MeteorConfig.class).asEagerSingleton();
        binder.bindType(ClusterDao.class).asEagerSingleton();
        binder.bindType(SessionService.class).asEagerSingleton();
        binder.bindType(ConsoleCookies.class).asEagerSingleton();
        binder.bindType(CommandDispatcher.class).asEagerSingleton();
        binder.setEncodingCharacter("UTF-8", "UTF-8");
        binder.addRender("json").to(ConsoleJsonRender.class);
        binder.filter("/api/*").through(binder.getBindInfo(ConsoleApiFilter.class));
        binder.bindType(RpcServer.class).asEagerSingleton();
    }

    @Bean
    public ConsoleApiFilter apiFilter(SessionService sessions, ConsoleCookies cookies) {
        return new ConsoleApiFilter(sessions, cookies);
    }

    private static Path configuration() throws NoSuchFileException {
        String configured = System.getProperty("config");
        if (StringUtils.isBlank(configured)) {
            return Path.of(System.getProperty("user.home"), ".meteor", "meteor.properties").toAbsolutePath().normalize();
        }

        Path configuration = Path.of(configured);
        if (!configuration.isAbsolute()) {
            URL resource = ConsoleMain.class.getResource("ConsoleMain.class");
            if (resource != null && StringUtils.equals(resource.getProtocol(), "jar")) {
                // Hasor Boot 的嵌套资源保留外层 JAR 地址，不能依赖应用类的 CodeSource。
                String archive = resource.getFile();
                Path jar = Path.of(URI.create(archive.substring(0, archive.indexOf("!/"))));
                configuration = jar.getParent().resolve(configuration);
            }
        }

        configuration = configuration.toAbsolutePath().normalize();
        if (!Files.isRegularFile(configuration)) {
            throw new NoSuchFileException(configuration.toString());
        } else {
            return configuration;
        }
    }
}
