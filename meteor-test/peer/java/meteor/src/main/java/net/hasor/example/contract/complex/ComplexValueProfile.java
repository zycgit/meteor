/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;
import java.io.IOException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.complex.onc.OncComplexBridge;
import net.hasor.example.contract.complex.onc.OncComplexCodecs;
import net.hasor.example.contract.complex.onc.OncComplexService;
import net.hasor.example.contract.complex.thrift.ThriftComplexBridge;
import net.hasor.example.contract.complex.thrift.generated.ThriftComplexValueService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;

/** 明确选择可承载普通 Java DTO 的编码，不把无 schema 的 DTO 宣称为 Protobuf。 */
public enum ComplexValueProfile {
    RSF_JSON("rsf-json", "rsf", "tcp", "Json"),
    DUBBO_HESSIAN2("dubbo-hessian2", "dubbo", "tcp", "Hessian2"),
    HPROSE("hprose", "hprose", "http", "Hprose"),
    HESSIAN2("hessian-2", "hessian", "http", "Hessian"),
    JSONRPC("jsonrpc-json", "jsonrpc", "http", "Json"),
    XMLRPC("xmlrpc-struct", "xmlrpc", "http", "Json"),
    SOAP_LITERAL("soap-literal", "soap", "http", "Xml"),
    GRPC_JSON("grpc-custom-json", "grpc", "http", "Json"),
    GRPC_PROTOBUF("grpc-protobuf", "grpc", "http", "Protobuf"),
    GRPC_WEB_JSON("grpc-web-custom-json", "grpc-web", "http", "Json"),
    GRPC_WEB_PROTOBUF("grpc-web-protobuf", "grpc-web", "http", "Protobuf"),
    CONNECT_JSON("connect-json", "connect", "http", "Json"),
    CONNECT_PROTOBUF("connect-protobuf", "connect", "http", "Protobuf"),
    TRIPLE_HESSIAN2("triple-java-hessian2", "triple", "http", "Hessian2"),
    TRIPLE_PROTOBUF("triple-protobuf", "triple", "http", "Protobuf"),
    THRIFT_BINARY("thrift-binary-framed", "thrift", "tcp", "thrift"),
    ONCRPC_XDR("oncrpc-xdr", "oncrpc", "tcp", "oncrpc");

    private final String profile;
    private final String protocol;
    private final String transport;
    private final String serialization;

    ComplexValueProfile(String profile, String protocol, String transport, String serialization) {
        this.profile = profile;
        this.protocol = protocol;
        this.transport = transport;
        this.serialization = serialization;
    }

    public String protocolName() {
        return this.protocol;
    }

    public MetConfiguration providerConfiguration() {
        return this.configuration(true);
    }

    public MetConfiguration consumerConfiguration() {
        return this.configuration(false);
    }

    private MetConfiguration configuration(boolean listener) {
        // 业务端口仅挂载当前 profile，消费应用只有出站能力。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("complex_contract").setDefaultProtocol(this.protocol);
        ConnectorSettings connector = configuration.connector(this.transport);
        if (listener) {
            connector.bind("127.0.0.1", 0);
        }
        if (this == GRPC_JSON || this == GRPC_PROTOBUF) {
            connector.option("httpVersion", 2);
        }
        if (this == TRIPLE_HESSIAN2 || this == TRIPLE_PROTOBUF) {
            connector.option("httpVersion", 2);
        }
        var mount = connector.protocol(this.protocol).option("serializeType", this.serialization);
        if (StringUtils.equals(this.transport, "http")) {
            mount.contextPath("/complex");
        }
        if (this == HESSIAN2) {
            mount.option("version", 2);
        }
        if (this == TRIPLE_HESSIAN2 || this == TRIPLE_PROTOBUF) {
            mount.option("httpMode", "grpc");
        }
        if (this == THRIFT_BINARY) {
            mount.option("encoding", "binary").option("framed", true);
        }
        return configuration;
    }

    public MetBindInfo<?> publish(MetContext context, ComplexValueService implementation) throws IOException {
        // 强 IDL 的适配器仅转换边界签名，实际业务行为仍由传入的同一实现完成。
        if (this == THRIFT_BINARY) {
            ThriftComplexBridge bridge = new ThriftComplexBridge(implementation);
            return this.configure(context.publisher().rsfService(ThriftComplexValueService.Iface.class, bridge)) //
                    .register();
        }
        if (this == ONCRPC_XDR) {
            OncComplexBridge bridge = new OncComplexBridge(implementation);
            return this.configure(context.publisher().rsfService(OncComplexService.class, bridge)).register();
        }
        return this.configure(context.publisher().rsfService(ComplexValueService.class, implementation)).register();
    }

    public MetBindInfo<?> subscribe(MetContext context) throws IOException {
        if (this == THRIFT_BINARY) {
            return this.configure(context.publisher().rsfService(ThriftComplexValueService.Iface.class)).register();
        }
        if (this == ONCRPC_XDR) {
            return this.configure(context.publisher().rsfService(OncComplexService.class)).register();
        }
        return this.configure(context.publisher().rsfService(ComplexValueService.class)).register();
    }

    public ComplexValueService remote(MetClient client, MetBindInfo<?> subscription) {
        if (this == THRIFT_BINARY) {
            var remote = (ThriftComplexValueService.Iface) new ThriftClient(client).getRemote(subscription);
            return ThriftComplexBridge.remote(remote);
        }
        return (ComplexValueService) client.getRemote(subscription);
    }

    private <T> ConfigurationBuilder<T> configure(ConfigurationBuilder<T> builder) throws IOException {
        // 统一业务身份，编码与协议身份在提供者和消费者上显式一致。
        builder.name("meteor.contract.ComplexValueService").protocol(this.protocol).timeout(5000);
        builder.serialize(this.serialization);
        if (this == DUBBO_HESSIAN2 || this == TRIPLE_HESSIAN2) {
            builder.aliasName(this.protocol, ComplexValueService.class.getName());
        } else if (this == GRPC_JSON || this == GRPC_WEB_JSON || this == CONNECT_JSON || this.usesProtobuf()) {
            builder.aliasName(this.protocol, "meteor.contract.ComplexValueService");
        } else if (this == THRIFT_BINARY || StringUtils.equals(this.transport, "http")) {
            builder.aliasName(this.protocol, "complexValues");
        }

        // 每次绑定独立解析匹配的显式 schema，普通 DTO 不依赖未声明的 Protobuf 推断。
        if (this.usesProtobuf()) {
            var schema = ComplexValueProfile.class.getResource("/vectors/complex/values.proto");
            builder.bindResource(this.protocol, new GrpcProtoResource(schema));
        } else if (this == THRIFT_BINARY) {
            builder.bindResource(this.protocol, new ThriftServiceResource(ThriftComplexValueService.class));
        } else if (this == ONCRPC_XDR) {
            builder.bindResource(this.protocol, OncComplexCodecs.resource());
        }
        return builder;
    }

    private boolean usesProtobuf() {
        return this == GRPC_PROTOBUF || this == GRPC_WEB_PROTOBUF //
                || this == CONNECT_PROTOBUF || this == TRIPLE_PROTOBUF;
    }

    @Override
    public String toString() {
        return this.profile;
    }
}
