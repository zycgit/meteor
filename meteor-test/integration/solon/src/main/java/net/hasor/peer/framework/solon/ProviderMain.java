/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.framework.solon;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.example.integration.FrameworkOrderServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.peer.framework.FrameworkPeerUtils;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.annotation.SolonMain;
import org.noear.solon.core.BeanWrap;

/** Solon provider，参数为配置文件和结果文件。 */
@SolonMain
public class ProviderMain {
    public static void main(String[] args) throws Exception {
        Properties settings = FrameworkPeerUtils.settings(args[0]);
        SolonApp app = Solon.start(ProviderMain.class, new String[0], boot -> {
            boot.cfg().loadAdd(Path.of(args[0]).toUri().toString());
            boot.context().wrapAndPut(FrameworkOrderServiceImpl.class, new FrameworkOrderServiceImpl());
            if (StringUtils.equals(settings.getProperty("test.backend"), "jdbc")) {
                DataSource database = FrameworkPeerUtils.database(settings);
                boot.context().putWrap("registryDb", new BeanWrap(boot.context(), DataSource.class, database));
            }

            if (StringUtils.equals(settings.getProperty("test.backend"), "dubbo")) {
                boot.context().wrapAndPut(MetExtensionFactory.class, FrameworkPeerUtils.dubbo(settings));
            }
        });

        try {
            MetContext meteor = app.context().getBean(MetContext.class);
            FrameworkPeerUtils.ready(meteor, Path.of(args[1]));
            new CountDownLatch(1).await();
        } finally {
            Solon.stopBlock(false, 0);
        }
    }
}
