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
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.peer.framework.FrameworkPeerUtils;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.annotation.SolonMain;
import org.noear.solon.core.BeanWrap;

/** Solon consumer，参数为配置文件和结果文件。 */
@SolonMain
public class ConsumerMain {
    public static void main(String[] args) throws Exception {
        Properties settings = FrameworkPeerUtils.settings(args[0]);
        SolonApp app = Solon.start(ConsumerMain.class, new String[0], boot -> {
            boot.cfg().loadAdd(Path.of(args[0]).toUri().toString());
            boot.context().beanMake(OrderClient.class);
            if (StringUtils.equals(settings.getProperty("test.backend"), "jdbc")) {
                DataSource database = FrameworkPeerUtils.database(settings);
                boot.context().putWrap("registryDb", new BeanWrap(boot.context(), DataSource.class, database));
            }

            if (StringUtils.equals(settings.getProperty("test.backend"), "dubbo")) {
                boot.context().wrapAndPut(MetExtensionFactory.class, FrameworkPeerUtils.dubbo(settings));
            }
        });

        try {
            OrderClient client = app.context().getBean(OrderClient.class);
            client.invoke(Path.of(args[1]));
        } finally {
            Solon.stopBlock(false, 0);
        }
    }
}
