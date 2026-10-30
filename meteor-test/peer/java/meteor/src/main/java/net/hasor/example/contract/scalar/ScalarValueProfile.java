/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.io.IOException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.scalar.onc.OncScalarBridge;
import net.hasor.example.contract.scalar.onc.OncScalarCodecs;
import net.hasor.example.contract.scalar.onc.OncScalarService;
import net.hasor.example.contract.scalar.thrift.ThriftScalarBridge;
import net.hasor.example.contract.scalar.thrift.generated.ThriftScalarValueService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;

/** 独立标量契约的公开启动配置，显式绑定各编码的业务类型映射。 */
public enum ScalarValueProfile {
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

    ScalarValueProfile(String profile, String protocol, String transport, String serialization) {
        this.profile = profile;
        this.protocol = protocol;
        this.transport = transport;
        this.serialization = serialization;
    }

    public String protocolName() {
        return this.protocol;
    }

    public boolean isXmlRpc() {
        return this == XMLRPC;
    }

    public MetConfiguration providerConfiguration() {
        return this.configuration(true);
    }

    public MetConfiguration consumerConfiguration() {
        return this.configuration(false);
    }

    private MetConfiguration configuration(boolean listener) {
        // 每次只挂载当前编码，消费端通过正式出站连接调用业务端口。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("scalar_contract").setDefaultProtocol(this.protocol);
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
            mount.contextPath("/scalar");
        }
        if (this == HESSIAN2) {
            mount.option("version", 2);
        }
        if (this == XMLRPC) {
            mount.option("allowNil", true);
        }
        if (this == TRIPLE_HESSIAN2 || this == TRIPLE_PROTOBUF) {
            mount.option("httpMode", "grpc");
        }
        if (this == THRIFT_BINARY) {
            mount.option("encoding", "binary").option("framed", true);
        }
        return configuration;
    }

    public MetBindInfo<?> publish(MetContext context, ScalarValueService implementation) throws IOException {
        // IDL 桥接只转换协议签名，业务处理仍委托同一份共享实现。
        if (this == THRIFT_BINARY) {
            ThriftScalarBridge bridge = new ThriftScalarBridge(implementation);
            return this.configure(context.publisher().rsfService(ThriftScalarValueService.Iface.class, bridge)) //
                    .register();
        }
        if (this == ONCRPC_XDR) {
            OncScalarBridge bridge = new OncScalarBridge(implementation);
            return this.configure(context.publisher().rsfService(OncScalarService.class, bridge)).register();
        }
        return this.configure(context.publisher().rsfService(ScalarValueService.class, implementation)).register();
    }

    public MetBindInfo<?> subscribe(MetContext context) throws IOException {
        if (this == THRIFT_BINARY) {
            return this.configure(context.publisher().rsfService(ThriftScalarValueService.Iface.class)).register();
        }
        if (this == ONCRPC_XDR) {
            return this.configure(context.publisher().rsfService(OncScalarService.class)).register();
        }
        return this.configure(context.publisher().rsfService(ScalarValueService.class)).register();
    }

    public ScalarValueService remote(MetClient client, MetBindInfo<?> subscription) {
        if (this == THRIFT_BINARY) {
            var remote = (ThriftScalarValueService.Iface) new ThriftClient(client).getRemote(subscription);
            return ThriftScalarBridge.remote(remote);
        }
        return (ScalarValueService) client.getRemote(subscription);
    }

    private <T> ConfigurationBuilder<T> configure(ConfigurationBuilder<T> builder) throws IOException {
        // 使用独立服务身份，防止与复杂类型矩阵共享发布或订阅状态。
        builder.name("meteor.scalar.ScalarValueService").protocol(this.protocol).timeout(5000);
        builder.serialize(this.serialization);
        if (this == DUBBO_HESSIAN2 || this == TRIPLE_HESSIAN2) {
            builder.aliasName(this.protocol, ScalarValueService.class.getName());
        } else if (this == GRPC_JSON || this == GRPC_WEB_JSON || this == CONNECT_JSON || this.usesProtobuf()) {
            builder.aliasName(this.protocol, "meteor.scalar.ScalarValueService");
        } else if (this == THRIFT_BINARY || StringUtils.equals(this.transport, "http")) {
            builder.aliasName(this.protocol, "scalarValues");
        }

        // Protobuf、Thrift 与 ONC 均使用独立的显式契约资源。
        if (this.usesProtobuf()) {
            var schema = ScalarValueProfile.class.getResource("/protocol/proto/scalar/values.proto");
            builder.bindResource(this.protocol, new GrpcProtoResource(schema));
        } else if (this == THRIFT_BINARY) {
            builder.bindResource(this.protocol, new ThriftServiceResource(ThriftScalarValueService.class));
        } else if (this == ONCRPC_XDR) {
            builder.bindResource(this.protocol, OncScalarCodecs.resource());
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
