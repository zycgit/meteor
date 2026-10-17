/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.soap.channel.SoapEndpointRouter;
import net.hasor.meteor.protocol.soap.channel.SoapSession;
import net.hasor.meteor.protocol.soap.codec.SoapMessageCodec;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionResource;
import net.hasor.meteor.protocol.soap.definition.SoapOperation;
import net.hasor.meteor.protocol.soap.definition.SoapPart;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;

/** SOAP 1.1/1.2 协议入口及 Java 服务映射校验。 */
public final class SoapProtocol implements ProtocolFactory<HttpExchange> {
    private final XmlMapper                                                        xmlMapper  = SoapXmlMapping.newMapper();
    private final SoapXmlMapping                                                   xmlMapping = new SoapXmlMapping(this.xmlMapper);
    private final Map<ProtocolConfig, Map<MetBindInfo<?>, SoapDefinitionResource>> resources  = new ConcurrentHashMap<>();
    private final Map<ProtocolConfig, SoapMessageCodec>                            codecs     = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "soap";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerializeType() {
        return "Xml";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        this.validateAttachments(config);
        String alias = service.getAliasName(this.name());
        if (StringUtils.isBlank(alias)) {
            throw new IllegalArgumentException("SOAP service must declare a soap alias");
        }
        if (!this.isPathSegment(alias)) {
            throw new IllegalArgumentException("SOAP service alias must be one URI path segment: " + alias);
        }
        this.validateDirectoryPath(config, service);
        SoapDefinitionResource resource = SoapDefinitionResource.create(config, service);
        boolean externalDefinition = service.getBindResource() instanceof SoapDefinitionResource;
        SoapVersion version = SoapVersion.from(config);

        // Java 方法和 WSDL operation 必须一一对应；重载无法映射到 SOAP operation 名称。
        Set<String> methods = new HashSet<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (!methods.add(method.getName())) {
                String message = "SOAP service does not support overloaded method: " + method.getName();
                throw new IllegalArgumentException(message);
            }

            SoapOperation operation = resource.definition().operation(method.getName());
            if (operation == null) {
                throw new IllegalArgumentException("WSDL operation not found for Java method: " + method.getName());
            }
            if (operation.inputBody().parts().size() != method.getParameterCount()) {
                String message = "WSDL parameter count does not match Java method: " + method.getName();
                throw new IllegalArgumentException(message);
            }
            this.requirePartTypes(operation.inputBody().parts(), method.getGenericParameterTypes(), method.getName());
            if (externalDefinition) {
                SoapVersion encoding = operation.inputBody().encoded() ? version : null;
                this.requireXmlTypes(method.getGenericParameterTypes(), encoding);
            }
            if (operation.oneWay() && method.getReturnType() != void.class) {
                throw new IllegalArgumentException("SOAP one-way method must return void: " + method.getName());
            }
            boolean returnsValue = method.getReturnType() != void.class;
            boolean declaresResult = !operation.oneWay() && !operation.outputBody().parts().isEmpty();
            if (!operation.oneWay() && returnsValue != declaresResult) {
                String message = "WSDL result does not match Java return type: " + method.getName();
                throw new IllegalArgumentException(message);
            }
            if (declaresResult) {
                Type[] resultTypes = SoapMessageCodec.resultTypes( //
                        operation.outputBody(), method.getGenericReturnType());
                this.requirePartTypes(operation.outputBody().parts(), resultTypes, method.getName());
                if (externalDefinition) {
                    SoapVersion encoding = operation.outputBody().encoded() ? version : null;
                    this.requireXmlTypes(resultTypes, encoding);
                }
            }
            boolean messageMode = service.isMessage() || method.isAnnotationPresent(MetMessage.class);
            if (method.isAnnotationPresent(SoapGet.class)) {
                if (method.getParameterCount() != 0) {
                    String message = "SOAP GET method must not declare parameters: ";
                    throw new IllegalArgumentException(message + method.getName());
                }
                if (messageMode) {
                    String message = "SOAP GET method must not use message mode: ";
                    throw new IllegalArgumentException(message + method.getName());
                }
            }
            if (messageMode != operation.oneWay()) {
                String message = "SOAP message mode does not match WSDL operation: " + method.getName();
                throw new IllegalArgumentException(message);
            }
        }

        for (SoapOperation operation : resource.definition().operations()) {
            if (!methods.contains(operation.name())) {
                throw new IllegalArgumentException("Java method not found for WSDL operation: " + operation.name());
            }
        }
        this.cacheResource(config, service, resource);
    }

    private void requireXmlTypes(Type[] types, SoapVersion encodingVersion) {
        // 自动生成契约时已遍历类型图；外部 WSDL 同样要在发布前发现嵌套 DTO 的映射错误。
        Set<JavaType> visited = new HashSet<>();
        for (Type type : types) {
            this.requireXmlType(this.xmlMapper.constructType(type), encodingVersion, visited);
        }
    }

    private void requireXmlType(JavaType type, SoapVersion encodingVersion, Set<JavaType> visited) {
        if (SoapXmlMapping.isScalar(type) || !visited.add(type)) {
            return;
        }
        if (SoapXmlMapping.isCollection(type)) {
            JavaType content = type.getContentType();
            if (content == null || content.hasRawClass(Object.class)) {
                throw new IllegalArgumentException("SOAP collection elements require a concrete Java type");
            }
            if (encodingVersion == null && SoapXmlMapping.isCollection(content)) {
                throw new IllegalArgumentException("Nested SOAP literal collections require an explicit DTO wrapper");
            }
            this.requireXmlType(content, encodingVersion, visited);
            return;
        }
        for (SoapXmlProperty property : this.xmlMapping.type(type).getProperties()) {
            boolean literalShape = property.isText() || property.getWrapper() != null;
            if (encodingVersion != null && literalShape) {
                throw new IllegalArgumentException("SOAP encoded values do not support XML text or element wrappers");
            }
            if (encodingVersion != null && property.isAttribute()) {
                this.requireEncodedAttribute(property.getName(), encodingVersion);
            }
            this.requireXmlType(property.getJavaType(), encodingVersion, visited);
        }
    }

    private void requireEncodedAttribute(QName name, SoapVersion version) {
        if (version.isEncodingControlAttribute(name)) {
            throw new IllegalArgumentException("SOAP encoded XML attribute is reserved: " + name);
        }
    }

    private void validateAttachments(ProtocolConfig config) {
        String mode = config.option("attachmentMode", "inline");
        if (!Set.of("inline", "mtom", "swa").contains(mode)) {
            throw new IllegalArgumentException("SOAP attachmentMode must be inline, mtom or swa");
        }
        int threshold;
        try {
            threshold = Integer.parseInt(config.option("attachmentThreshold", "1024"));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("SOAP attachmentThreshold must be an integer", failure);
        }
        if (threshold < 0) {
            throw new IllegalArgumentException("SOAP attachmentThreshold must not be negative");
        }
    }

    private void validateDirectoryPath(ProtocolConfig config, MetBindInfo<?> service) {
        String directory = SoapEndpointRouter.directoryPath(config);
        if (directory == null) {
            return;
        }

        // 目录与业务服务使用不同的入口，避免打开目录后遮蔽已有服务。
        for (String endpoint : SoapEndpointRouter.serviceEndpoints(config, service)) {
            if (StringUtils.equals(directory, endpoint)) {
                throw new IllegalArgumentException("SOAP service endpoint conflicts with directoryPath: " + directory);
            }
        }
    }

    private void requirePartTypes(List<SoapPart> parts, Type[] types, String method) {
        for (int index = 0; index < parts.size(); index++) {
            SoapPart part = parts.get(index);
            boolean repeated = part.maxOccurs() < 0 || part.maxOccurs() > 1;
            if (repeated && !this.isRepeatedType(types[index])) {
                String message = "Repeated SOAP part requires an array or collection: ";
                throw new IllegalArgumentException(message + method + " -> " + part.name());
            }
            if (part.minOccurs() == 0 && !repeated && types[index] instanceof Class<?> type && type.isPrimitive()) {
                String message = "Optional SOAP part requires a nullable Java type: ";
                throw new IllegalArgumentException(message + method + " -> " + part.name());
            }
        }
    }

    private boolean isRepeatedType(Type type) {
        if (type instanceof GenericArrayType) {
            return true;
        }
        if (type instanceof ParameterizedType parameterized) {
            type = parameterized.getRawType();
        }
        return type instanceof Class<?> raw && (raw.isArray() || Collection.class.isAssignableFrom(raw));
    }

    private boolean isPathSegment(String alias) {
        if (StringUtils.equals(".", alias) || StringUtils.equals("..", alias)) {
            return false;
        }
        for (int index = 0; index < alias.length(); index++) {
            char character = alias.charAt(index);
            boolean letter = character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z';
            boolean digit = character >= '0' && character <= '9';
            boolean mark = character == '-' || character == '.' || character == '_' || character == '~';
            if (!letter && !digit && !mark) {
                return false;
            }
        }
        return true;
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        SoapMessageCodec codec = this.codecs.computeIfAbsent(config, value -> //
                new SoapMessageCodec(value, context.metContext().getExtension(SoapHeaderHandler.class)));
        return new SoapSession(config, context, connection, messages, codec, //
                service -> this.resource(config, service));
    }

    private SoapDefinitionResource resource(ProtocolConfig config, MetBindInfo<?> service) {
        Map<MetBindInfo<?>, SoapDefinitionResource> byService = this.resourceCache(config);
        synchronized (byService) {
            this.removePreviousDeclaration(byService, service);
            return byService.computeIfAbsent(service, value -> SoapDefinitionResource.create(config, value));
        }
    }

    private void cacheResource(ProtocolConfig config, MetBindInfo<?> service, SoapDefinitionResource resource) {
        Map<MetBindInfo<?>, SoapDefinitionResource> byService = this.resourceCache(config);
        synchronized (byService) {
            this.removePreviousDeclaration(byService, service);
            byService.put(service, resource);
        }
    }

    private Map<MetBindInfo<?>, SoapDefinitionResource> resourceCache(ProtocolConfig config) {
        return this.resources.computeIfAbsent(config, value -> new WeakHashMap<>());
    }

    private void removePreviousDeclaration(Map<MetBindInfo<?>, SoapDefinitionResource> byService, //
            MetBindInfo<?> service) {
        // 重新发布同一服务时淘汰旧声明，缓存只随当前声明和协议挂载存在。
        byService.keySet().removeIf(previous -> previous != service //
                && StringUtils.equals(previous.getBindID(), service.getBindID()));
    }
}
