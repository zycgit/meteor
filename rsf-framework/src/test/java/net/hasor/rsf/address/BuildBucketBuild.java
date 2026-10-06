/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
import java.util.ArrayList;

/**
 *
 * @version : 2016年09月09日
 * @author 赵永春 (zyc@hasor.net)
 */
public class BuildBucketBuild {
    private String        serviceID;
    private AddressBucket bucket;

    public BuildBucketBuild(String serviceID) {
        this.serviceID = serviceID;
    }

    public AddressBucket getBucket() {
        return this.bucket;
    }

    public BuildBucketBuild invoke() {
        this.bucket = new AddressBucket(this.serviceID, "default");

        ArrayList<InterAddress> dynamicList = new ArrayList<>();
        dynamicList.add(new InterAddress("127.0.0.1", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.2", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.3", 8000, "etc2"));
        dynamicList.add(new InterAddress("127.0.0.4", 8000, "etc2"));
        this.bucket.newAddress(dynamicList, AddressTypeEnum.Dynamic);

        ArrayList<InterAddress> staticList = new ArrayList<>();
        staticList.add(new InterAddress("127.0.1.1", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.2.2", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.3.3", 8000, "etc2"));
        staticList.add(new InterAddress("127.0.4.4", 8000, "etc2"));
        this.bucket.newAddress(staticList, AddressTypeEnum.Static);
        return this;
    }
}