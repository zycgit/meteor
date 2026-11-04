/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework.hasor;
import java.nio.file.Path;
import java.util.Properties;
import javax.sql.DataSource;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.core.ApiBinder;
import net.hasor.core.Module;
import net.hasor.example.integration.FrameworkOrderServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.meteor.MeteorModule;
import net.hasor.peer.framework.FrameworkPeerUtils;

/** Hasor Boot provider，参数为配置文件和结果文件。 */
public class ProviderMain implements Module {
    public static void main(String[] args) throws Exception {
        String location = Path.of(args[0]).toUri().toString();
        Boot boot = new Boot().hconfigFile(location).sources(ProviderMain.class);
        try (BootApplication app = boot.start()) {
            MetContext meteor = app.getAppContext().getInstance(MetContext.class);
            FrameworkPeerUtils.ready(meteor, Path.of(args[1]));
            app.join();
        }
    }

    @Override
    public void loadModule(ApiBinder binder) throws Throwable {
        binder.installModule(new MeteorModule());
        binder.bindType(FrameworkOrderServiceImpl.class).toInstance(new FrameworkOrderServiceImpl());
        String backend = binder.getSettings().getString("test.backend");
        Properties settings = new Properties();
        if (StringUtils.equals(backend, "jdbc")) {
            settings.setProperty("test.jdbc.url", binder.getSettings().getString("test.jdbc.url"));
            DataSource database = FrameworkPeerUtils.database(settings);
            binder.bindType(DataSource.class).nameWith("registryDb").toInstance(database);
        }

        if (StringUtils.equals(backend, "dubbo")) {
            settings.setProperty("test.dubbo.address", binder.getSettings().getString("test.dubbo.address"));
            settings.setProperty("test.dubbo.group", binder.getSettings().getString("test.dubbo.group"));
            settings.setProperty("test.publish-host", binder.getSettings().getString("test.publish-host"));
            binder.bindType(MetExtensionFactory.class).toInstance(FrameworkPeerUtils.dubbo(settings));
        }
    }
}
