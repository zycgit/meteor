/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 复用既有协议配置，另列 Java object-stream，不与 JSON 默认值混为一项。 */
public enum JavaTypeProfile {
    RSF_JSON(ScalarValueProfile.RSF_JSON),
    RSF_JAVA(null),
    DUBBO_HESSIAN2(ScalarValueProfile.DUBBO_HESSIAN2),
    HPROSE(ScalarValueProfile.HPROSE),
    HESSIAN2(ScalarValueProfile.HESSIAN2),
    JSONRPC(ScalarValueProfile.JSONRPC),
    XMLRPC(ScalarValueProfile.XMLRPC),
    SOAP_LITERAL(ScalarValueProfile.SOAP_LITERAL),
    GRPC_JSON(ScalarValueProfile.GRPC_JSON),
    GRPC_PROTOBUF(ScalarValueProfile.GRPC_PROTOBUF),
    GRPC_WEB_JSON(ScalarValueProfile.GRPC_WEB_JSON),
    GRPC_WEB_PROTOBUF(ScalarValueProfile.GRPC_WEB_PROTOBUF),
    CONNECT_JSON(ScalarValueProfile.CONNECT_JSON),
    CONNECT_PROTOBUF(ScalarValueProfile.CONNECT_PROTOBUF),
    TRIPLE_HESSIAN2(ScalarValueProfile.TRIPLE_HESSIAN2),
    TRIPLE_PROTOBUF(ScalarValueProfile.TRIPLE_PROTOBUF),
    THRIFT_BINARY(ScalarValueProfile.THRIFT_BINARY),
    ONCRPC_XDR(ScalarValueProfile.ONCRPC_XDR);

    private final ScalarValueProfile profile;

    JavaTypeProfile(ScalarValueProfile profile) {
        this.profile = profile;
    }

    public String protocolName() {
        return this.profile == null ? "rsf" : this.profile.protocolName();
    }

    public MetConfiguration providerConfiguration() {
        return this.configuration(true);
    }

    public MetConfiguration consumerConfiguration() {
        return this.configuration(false);
    }

    private MetConfiguration configuration(boolean listening) {
        if (this.profile != null) {
            return listening ? this.profile.providerConfiguration() : this.profile.consumerConfiguration();
        }
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("java_types").setDefaultProtocol("rsf");
        var connector = configuration.connector("tcp");
        if (listening) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("rsf").option("serializeType", "Java");
        return configuration;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public MetBindInfo<?> publish(MetContext context, Class<?> contract, Object implementation) {
        return this.configure(context.publisher().rsfService((Class) contract, implementation), contract).register();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public MetBindInfo<?> subscribe(MetContext context, Class<?> contract) {
        return this.configure(context.publisher().rsfService((Class) contract), contract).register();
    }

    public <T> ConfigurationBuilder<T> configure(ConfigurationBuilder<T> builder, Class<?> contract) {
        // 同一类型的两端采用同一身份；所有定义均来自真实声明和公共注解。
        builder.name("meteor.types." + contract.getSimpleName()).protocol(this.protocolName()).timeout(5000);
        builder.serialize(this.serialization());
        String alias = "meteor.types.JavaTypeService";
        if (this == DUBBO_HESSIAN2 || this == TRIPLE_HESSIAN2) {
            alias = contract.getName();
        } else if (this == SOAP_LITERAL || this == XMLRPC || this == JSONRPC || this == HPROSE //
                || this == HESSIAN2 || this == THRIFT_BINARY) {
            alias = "javaTypes";
        }
        return builder.aliasName(this.protocolName(), alias);
    }

    private String serialization() {
        return switch (this) {
            case RSF_JAVA -> "Java";
            case DUBBO_HESSIAN2, TRIPLE_HESSIAN2 -> "Hessian2";
            case HESSIAN2 -> "Hessian";
            case HPROSE -> "Hprose";
            case SOAP_LITERAL -> "Xml";
            case GRPC_PROTOBUF, GRPC_WEB_PROTOBUF, CONNECT_PROTOBUF, TRIPLE_PROTOBUF -> "Protobuf";
            case THRIFT_BINARY -> "thrift";
            case ONCRPC_XDR -> "oncrpc";
            default -> "Json";
        };
    }

    public static List<JavaTypeProfile> selected() {
        // 过滤器只选择配置，不把未实现或失败场景改成跳过。
        String profiles = System.getProperty("meteor.matrix.javaTypeProfiles", "*");
        String protocols = System.getProperty("meteor.matrix.protocols", "*");
        List<JavaTypeProfile> result = new ArrayList<>();
        for (JavaTypeProfile profile : values()) {
            boolean selectedProfile = matches(profiles, profile.toString());
            boolean protocolName = matches(protocols, profile.protocolName());
            boolean profileName = matches(protocols, profile.toString());
            boolean selectedProtocol = protocolName || profileName;
            if (selectedProfile && selectedProtocol) {
                result.add(profile);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The Java type matrix selection has no matching profiles");
        }
        return result;
    }

    public static boolean selectedType(String type) {
        return matches(System.getProperty("meteor.matrix.javaTypes", "*"), type);
    }

    private static boolean matches(String expression, String name) {
        for (String selected : expression.toLowerCase(Locale.ROOT).split(",")) {
            String token = selected.trim();
            if (StringUtils.equals(token, "*") || StringUtils.equals(token, "all") || StringUtils.equals(token, name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return this.profile == null ? "rsf-java" : this.profile.toString();
    }
}
