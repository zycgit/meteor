/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import java.net.URL;

/** 本地 .proto 资源入口；构造时解析并校验，不生成 Java 类，也不参与注册中心同步。 */
public final class GrpcProtoResource {
    final GrpcProtoSchema schema;

    public GrpcProtoResource(URL source) throws IOException {
        this.schema = new GrpcProtoSchema(source);
    }
}
