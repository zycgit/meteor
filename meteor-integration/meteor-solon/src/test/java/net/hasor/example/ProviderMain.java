/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.example.services.InventoryServiceImpl;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.MetContext;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.annotation.SolonMain;

/** 先启动提供者，再运行 ConsumerMain；监听 28770，退出进程时由 Solon 关闭服务。 */
@SolonMain
public class ProviderMain {
    public static void main(String[] args) {
        SolonApp app = Solon.start(ProviderMain.class, args, boot -> {
            boot.cfg().loadAdd("classpath:solon-provider.properties");
            boot.context().beanMake(OrderServiceImpl.class);
            boot.context().beanMake(InventoryServiceImpl.class);
        });
        MetContext meteor = app.context().getBean(MetContext.class);
        System.out.println("订单与库存服务已启动：" + meteor.getListenAddress("rsf"));
    }
}
