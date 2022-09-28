/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.address;
import net.hasor.cobble.NetworkUtils;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.io.IOUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

/**
 *
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public class AddressPoolTest {
    protected void configService(AddressPool pool, String service) throws IOException {
        NetworkUtils.finalBindAddress("local");
        //
        ArrayList<InterAddress> dynamicList = new ArrayList<>();
        dynamicList.add(new InterAddress("127.0.0.1", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.2", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.3", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.4", 8000, "etc2"));
        pool.appendAddress(service, dynamicList);
        //
        ArrayList<InterAddress> staticList = new ArrayList<>();
        staticList.add(new InterAddress("127.0.1.1", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.2.2", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.3.3", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.4.4", 8000, "etc2"));
        pool.appendStaticAddress(service, staticList);
        //
        String flowBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/full-flow.xml"), "utf-8");
        pool.updateFlowControl(service, flowBody);
        //
        String scriptBody1 = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/rule-script/service-level.groovy"), "utf-8");
        pool.updateServiceRoute(service, scriptBody1);
        //
        String scriptBody2 = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/rule-script/method-level.groovy"), "utf-8");
        pool.updateMethodRoute(service, scriptBody2);
        //
        String scriptBody3 = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/rule-script/args-level.groovy"), "utf-8");
        pool.updateArgsRoute(service, scriptBody3);
    }

    @Test
    public void nextAddressTest() throws IOException, InterruptedException {
        final AddressPool pool = new AddressPool("default", 3000);
        final String serviceID = "[RSF]test.net.hasor.rsf.services.EchoService-1.0.0";

        ArrayList<InterAddress> dynamicList = new ArrayList<>();
        dynamicList.add(new InterAddress("127.0.0.1", 8000, "etc2"));
        pool.appendAddress(serviceID, dynamicList);

        ArrayList<InterAddress> staticList = new ArrayList<>();
        staticList.add(new InterAddress("192.168.137.10", 8000, "etc2"));
        staticList.add(new InterAddress("192.168.137.11", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.4.4", 8000, "etc2"));
        pool.appendStaticAddress(serviceID, staticList);

        String flowBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/flow-control/pool-performance.xml"), "utf-8");
        pool.updateFlowControl(serviceID, flowBody);

        Thread thread = new Thread(() -> {
            Random random = new Random(System.currentTimeMillis());
            while (true) {
                InterAddress address = pool.nextAddress(serviceID, "sayHello", new Object[] { "hello" });
                System.out.println(Long.toHexString(random.nextLong()).toUpperCase() + "\t" + address);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();

        Thread.sleep(5000);
        flowBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/full-flow.xml"), "utf-8");
        pool.updateFlowControl(serviceID, flowBody);

        Thread.sleep(5000);
        String scriptBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/rule-script/service-level.groovy"), "utf-8");
        pool.updateServiceRoute(serviceID, scriptBody);

        Thread.sleep(5000);
        thread.stop();
    }

    @Test
    public void saveToZipTest() throws IOException {
        AddressPool pool = new AddressPool();
        String serviceID = "HelloWord_";

        for (int i = 0; i < 10; i++) {
            String service = serviceID + i;
            configService(pool, service);
        }

        //        File outFile = new File(rsfEnv.getPluginDir(AddressPoolTest.class), "pool.zip");
        //        outFile.getParentFile().mkdirs();
        //        FileOutputStream out = new FileOutputStream(outFile, false);
        //        pool.storeConfig(out);
        //        out.flush();
        //        out.close();
    }

    @Test
    public void readFormZipTest() throws IOException {
        this.saveToZipTest();
        //
        AddressPool pool = new AddressPool();
        String serviceID = "HelloWord_";
        //
        for (int i = 0; i < 10; i++) {
            pool.appendAddress(serviceID + i, new InterAddress("192.168.1.1", 8000, "etc2"));
        }
        //
        //        File inFile = new File(rsfEnv.getPluginDir(AddressPoolTest.class), "pool.zip");
        //        FileInputStream in = new FileInputStream(inFile);
        //        pool.restoreConfig(in);
        //        in.close();
        //
        Set<String> names = pool.getBucketNames();
        for (String service : names) {
            AddressBucket bucket = pool.getBucket(service);
            System.out.println(bucket.getServiceID() + " - address size = " + bucket.getAllAddresses().size());
        }
    }

    @Test
    public void localDiskCacheTest() throws IOException {
        // 1.修改默认配置
        AddressPool pool = new AddressPool("default", 500);
        DiskCache diskCache = new DiskCache(pool, new File(""), 1000, 3000);
        String serviceID = "HelloWord_";
        // 2.测试本地缓存保存
        for (int i = 0; i < 10; i++) {
            configService(pool, serviceID + i);
        }
        diskCache.storeConfig();
        diskCache.restoreConfig();
    }

}