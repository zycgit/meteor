/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.builtin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

public class GrpcBuiltinServiceNamesTest {
    @Test
    @Timeout(20)
    public void healthListIncludesTheDerivedServiceName() throws Exception {
        // 安装标准服务，业务提供者只声明服务名，不设置协议别名。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .option("streamBody", true);
        configuration.addExtension(new GrpcBuiltinServicesFactory());
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Echo> binding = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("example.Echo").serialize("Json").register();
            assertNull(binding.getAliasName("grpc"));

            // 健康状态使用实际协议名登记，不遗漏未声明别名的提供者。
            GrpcBuiltinServicesExtension services = context.getExtension(GrpcBuiltinServicesExtension.class);
            GrpcHealthEndpoint health = new GrpcHealthEndpoint(services, context);
            try {
                JsonNode response = health.List(new ObjectMapper().createObjectNode());
                JsonNode statuses = response.path("statuses");
                assertEquals("SERVING", statuses.path("example.Echo").path("status").asText());
                assertFalse(statuses.has(Echo.class.getName()));
            } finally {
                health.close();
            }
        }
    }
}
