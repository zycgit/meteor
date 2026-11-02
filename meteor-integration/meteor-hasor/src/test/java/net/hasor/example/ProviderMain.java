/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.core.ApiBinder;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.core.Module;
import net.hasor.example.services.InventoryServiceImpl;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MeteorModule;

/** 先运行本入口，再运行 ConsumerMain；默认监听 28760，停止进程后由容器释放资源。 */
public class ProviderMain implements Module {
    public static void main(String[] args) throws Throwable {
        Hasor hasor = Hasor.create()//
                .mainSettingWith("hasor-provider.properties")//
                .addModules(new MeteorModule(), new ProviderMain());

        try (AppContext app = hasor.build()) {
            MetContext meteor = app.getInstance(MetContext.class);
            System.out.println("订单与库存服务已启动：" + meteor.getListenAddress("rsf"));
            app.join();
        }
    }

    @Override
    public void loadModule(ApiBinder binder) {
        binder.bindType(OrderServiceImpl.class).asEagerSingleton();
        binder.bindType(InventoryServiceImpl.class).asEagerSingleton();
    }
}
