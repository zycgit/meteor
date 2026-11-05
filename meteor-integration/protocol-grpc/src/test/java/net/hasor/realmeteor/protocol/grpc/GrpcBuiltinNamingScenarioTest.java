/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinServicesFactory;
import net.hasor.meteor.protocol.grpc.builtin.GrpcReflectionService;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

public class GrpcBuiltinNamingScenarioTest {
    @Test
    @Timeout(20)
    public void reflectionListsTheServiceWithoutAnExplicitAlias() throws Exception {
        // 提供者的默认服务名与 proto 契约一致，双方都不额外配置别名。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<Echo> provider = server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("example.Echo").serialize("Protobuf").bindResource("grpc", resource).register();
            assertNull(provider.getAliasName("grpc"));

            // 通过真实 HTTP/2 双向流查询标准服务目录。
            String name = "grpc.reflection.v1.ServerReflection";
            MetBindInfo<GrpcReflectionService> binding = client.publisher().rsfService(GrpcReflectionService.class) //
                    .name(name).serialize("Protobuf").bindResource("grpc", this.standardResource()) //
                    .contextPathByExact("grpc", "/" + name + "/ServerReflectionInfo").register();
            GrpcReflectionService reflection = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);
            JsonNode request = new ObjectMapper().createObjectNode().put("listServices", "");
            List<JsonNode> replies = new ArrayList<>();
            MetCaller.consume(reflection.ServerReflectionInfo(MetCaller.fromIterable(List.of(request))), //
                    replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(1, replies.size());

            // 目录名称与实际发布名相同，不回退到 Java 接口名或遗漏该服务。
            JsonNode response = replies.get(0);
            assertFalse(response.has("errorResponse"), response.toString());
            List<String> names = new ArrayList<>();
            for (JsonNode service : response.path("listServicesResponse").path("service")) {
                names.add(service.path("name").asText());
            }
            assertTrue(names.contains("example.Echo"), names.toString());
            assertFalse(names.contains(Echo.class.getName()), names.toString());
        }
    }

    @Test
    @Timeout(20)
    public void reflectionFindsTheContractByTheDerivedServiceName() throws Exception {
        // 服务绑定已有 proto，运行时使用从声明推导的相同服务名。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("example.Echo").serialize("Protobuf").bindResource("grpc", resource).register();

            // 用有效协议名查询描述文件，验证目录名和 proto 符号保持一致。
            String name = "grpc.reflection.v1.ServerReflection";
            MetBindInfo<GrpcReflectionService> binding = client.publisher().rsfService(GrpcReflectionService.class) //
                    .name(name).serialize("Protobuf").bindResource("grpc", this.standardResource()) //
                    .contextPathByExact("grpc", "/" + name + "/ServerReflectionInfo").register();
            GrpcReflectionService reflection = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);
            JsonNode request = new ObjectMapper().createObjectNode().put("fileContainingSymbol", "example.Echo");
            List<JsonNode> replies = new ArrayList<>();
            MetCaller.consume(reflection.ServerReflectionInfo(MetCaller.fromIterable(List.of(request))), //
                    replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(1, replies.size());
            JsonNode response = replies.get(0);
            assertFalse(response.has("errorResponse"), response.toString());

            // 返回的原始描述仍保留契约包、服务名和方法列表。
            FileDescriptorProto contract = null;
            for (JsonNode encoded : response.path("fileDescriptorResponse").path("fileDescriptorProto")) {
                FileDescriptorProto file = FileDescriptorProto.parseFrom(encoded.binaryValue());
                if (StringUtils.equals("echo.proto", file.getName())) {
                    contract = file;
                }
            }
            assertNotNull(contract, response.toString());
            assertEquals("example", contract.getPackage());
            assertEquals("Echo", contract.getService(0).getName());
            assertEquals(3, contract.getService(0).getMethodCount());
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .option("streamBody", true);
        configuration.addExtension(new GrpcBuiltinServicesFactory());
        return configuration;
    }

    private MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").option("httpVersion", 2).protocol("grpc") //
                .option("streamBody", true);
        return configuration;
    }

    private GrpcProtoResource standardResource() throws Exception {
        String source = "/META-INF/meteor/protocol/grpc/meteor-grpc-services.proto";
        return new GrpcProtoResource(GrpcBuiltinServicesFactory.class.getResource(source));
    }
}
