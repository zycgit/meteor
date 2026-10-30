/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct.stream;
import java.io.IOException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** 点对点流式专题：Java 接口不变，选择 Protobuf 时才绑定 proto 资源。 */
public final class StreamRpcExample {
    private StreamRpcExample() {
    }

    public static MetBindInfo<CatalogStreamService> publish(MetContext context, String format) throws IOException {
        var publication = context.publisher() //
                .rsfService(CatalogStreamService.class, new CatalogStreamServiceImpl());
        configure(publication, format);
        return publication.register();
    }

    public static MetBindInfo<CatalogStreamService> subscribe(MetContext context, String format) throws IOException {
        var subscription = context.publisher().rsfService(CatalogStreamService.class);
        configure(subscription, format);
        return subscription.register();
    }

    private static void configure(ConfigurationBuilder<CatalogStreamService> service, String format) throws IOException {
        String coding;
        if (StringUtils.equalsIgnoreCase(format, "Protobuf")) {
            coding = "Protobuf";
        } else if (StringUtils.equalsIgnoreCase(format, "Json")) {
            coding = "Json";
        } else {
            throw new IllegalArgumentException("Stream example supports Json or Protobuf: " + format);
        }

        // 流式专题有独立服务身份，不把 Publisher 方法混入所有协议共享的订单契约。
        service.name("catalog-" + coding).protocol("grpc").serialize(coding);
        if (StringUtils.equals(coding, "Protobuf")) {
            service.aliasName("grpc", "meteor.example.stream.CatalogStreamService");
            var source = StreamRpcExample.class.getResource("/protocol/proto/catalog.proto");
            service.bindResource("grpc", new GrpcProtoResource(source));
        } else {
            service.aliasName("grpc", "meteor.example.stream.JsonCatalogStreamService");
        }
    }
}
