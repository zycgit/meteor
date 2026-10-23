/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.soap.annotation.SoapGet;
import net.hasor.meteor.protocol.soap.channel.SoapSession;
import net.hasor.meteor.protocol.soap.codec.SoapMessageCodec;
import net.hasor.meteor.protocol.soap.definition.*;
import net.hasor.meteor.protocol.soap.mapping.SoapMethodTypes;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;

/** SOAP 1.1/1.2 协议入口及 Java 服务映射校验。 */
public final class SoapProtocol implements HttpProtocolProvider {
    @Override
    public String name() {
        return "soap";
    }

    @Override
    public String defaultSerialize() {
        return "Xml";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        MetBindInfo<?> service = meta.getService();
        this.validateAttachments(config);
        String alias = service.getAliasName(this.name());
        if (StringUtils.isBlank(alias)) {
            return null;
        }
        if (!this.supports(service)) {
            return null;
        }
        if (!this.isPathSegment(alias)) {
            throw new IllegalArgumentException("SOAP service alias must be one URI path segment: " + alias);
        }
        XmlMapper template = context == null ? null : context.getExtension(XmlMapper.class);
        XmlMapper mapper = SoapXmlMapping.configuredMapper(template);
        SoapVersion version = SoapVersion.from(config);
        boolean externalDefinition = meta.getBindResource() instanceof SoapDefinitionResource;
        if (!externalDefinition) {
            // 先区分无效选项和无法映射的签名，再进入 WSDL/XSD 生成。
            SoapDefinitionResource.parameterStyle(config);
            SoapVersion encoding = StringUtils.equals("encoded", config.option("soapUse", "literal")) ? version : null;
            if (!this.supportsGeneratedTypes(service, encoding, mapper)) {
                return null;
            }
        }
        SoapDefinitionResource resource = SoapDefinitionResource.create(config, meta, mapper);

        // 先确认方法集合，再准备一次 Java 访问和参数顺序映射。
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            SoapOperation operation = resource.definition().operation(MetRuntimeUtils.getProtocolMethodName(method));
            if (operation == null || operation.inputBody().parts().size() != method.getParameterCount()) {
                return null;
            }
        }
        SoapProtocolMeta prepared = new SoapProtocolMeta(resource, new SoapXmlMapping(mapper), service);

        // Java 方法和 WSDL operation 必须一一对应；重载无法映射到 SOAP operation 名称。
        Set<String> methods = new HashSet<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            SoapMethodTypes methodTypes = prepared.types(method);
            methods.add(MetRuntimeUtils.getProtocolMethodName(method));

            SoapOperation operation = resource.definition().operation(MetRuntimeUtils.getProtocolMethodName(method));
            if (!this.supportsParts(operation.inputBody().parts(), prepared.wireParameterTypes(method), mapper)) {
                return null;
            }
            if (externalDefinition) {
                SoapVersion encoding = operation.inputBody().encoded() ? version : null;
                if (!this.supportsXmlTypes(methodTypes.getParameterTypes(), encoding, mapper)) {
                    return null;
                }
            }
            if (operation.oneWay() && method.getReturnType() != void.class) {
                return null;
            }
            boolean returnsValue = method.getReturnType() != void.class;
            boolean declaresResult = !operation.oneWay() && !operation.outputBody().parts().isEmpty();
            if (!operation.oneWay() && returnsValue != declaresResult) {
                return null;
            }
            if (declaresResult) {
                Type[] resultTypes;
                try {
                    resultTypes = SoapMessageCodec.resultTypes( //
                            operation.outputBody(), methodTypes.getReturnType(), mapper);
                } catch (UnsupportedOperationException incompatibleResult) {
                    // 合法 WSDL 的输出无法由当前 Java 返回类型承载，只拒绝此协议端点。
                    return null;
                }
                if (!this.supportsParts(operation.outputBody().parts(), resultTypes, mapper)) {
                    return null;
                }
                if (externalDefinition) {
                    SoapVersion encoding = operation.outputBody().encoded() ? version : null;
                    if (!this.supportsXmlTypes(resultTypes, encoding, mapper)) {
                        return null;
                    }
                }
            }
            boolean messageMode = service.isMessage() || ServiceMetaParser.method(method).isMessage();
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
                return null;
            }
        }

        for (SoapOperation operation : resource.definition().operations()) {
            if (!methods.contains(operation.name())) {
                return null;
            }
        }
        // 定义与描述复用同一份准备结果，绑定只在全部映射成功后创建。
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize());
        prepared.describeContract(description);
        return meta.build(config, prepared, description);
    }

    private boolean supports(MetBindInfo<?> service) {
        Set<String> methods = new HashSet<>();
        Set<String> javaNames = new HashSet<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            boolean duplicateJavaName = !javaNames.add(method.getName());
            boolean duplicateRemoteName = !methods.add(MetRuntimeUtils.getProtocolMethodName(method));
            boolean streamingResult = this.stream(method.getReturnType());
            if (duplicateJavaName || duplicateRemoteName || streamingResult) {
                return false;
            }
            if (method.getReturnType() == void.class && ServiceMetaParser.method(method).getReturnName() != null) {
                throw new IllegalArgumentException("Void SOAP method cannot declare MetReturn: " + method);
            }
            boolean message = service.isMessage() || ServiceMetaParser.method(method).isMessage();
            if (message && method.getReturnType() != void.class) {
                return false;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (this.stream(parameter)) {
                    return false;
                }
            }
        }
        return !methods.isEmpty();
    }

    private boolean stream(Class<?> type) {
        return Flow.Publisher.class.isAssignableFrom(type) || InputStream.class.isAssignableFrom(type) //
                || OutputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type) //
                || Writer.class.isAssignableFrom(type);
    }

    private boolean supportsGeneratedTypes(MetBindInfo<?> service, SoapVersion encodingVersion, XmlMapper mapper) {
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            SoapMethodTypes types = new SoapMethodTypes(mapper, service.getBindType(), method);
            if (!this.supportsXmlTypes(types.getParameterTypes(), encodingVersion, mapper)) {
                return false;
            }
            if (method.getReturnType() != void.class) {
                Type[] result = { types.getReturnType() };
                if (!this.supportsXmlTypes(result, encodingVersion, mapper)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean supportsXmlTypes(Type[] types, SoapVersion encodingVersion, XmlMapper mapper) {
        // 自动与外部契约共用类型图预检，生成前就拒绝无法承载的 Java 绑定。
        SoapXmlMapping mapping = new SoapXmlMapping(mapper);
        Set<JavaType> visited = new HashSet<>();
        for (Type type : types) {
            if (!this.supportsXmlType(mapper.constructType(type), encodingVersion, visited, mapping)) {
                return false;
            }
        }
        return true;
    }

    private boolean supportsXmlType(JavaType type, SoapVersion encodingVersion, //
            Set<JavaType> visited, SoapXmlMapping mapping) {
        if (encodingVersion != null && !mapping.supportsEncodedType(type)) {
            return false;
        }
        if (SoapXmlMapping.isScalar(type) || !visited.add(type)) {
            return true;
        }
        if (type.hasRawClass(Object.class)) {
            return encodingVersion == null;
        }
        if (SoapXmlMapping.isMap(type)) {
            if (encodingVersion != null) {
                return false;
            }
            JavaType key = type.getKeyType();
            if (key != null && !key.hasRawClass(String.class)) {
                return false;
            }
            mapping.type(type);
            return this.supportsXmlType(type.getContentType(), null, visited, mapping);
        }
        if (SoapXmlMapping.isCollection(type)) {
            JavaType content = type.getContentType();
            if (encodingVersion != null && (content == null || content.hasRawClass(Object.class))) {
                return false;
            }
            return content == null || this.supportsXmlType(content, encodingVersion, visited, mapping);
        }
        for (SoapXmlProperty property : mapping.type(type).getProperties()) {
            boolean literalShape = property.isText() || property.getWrapper() != null;
            if (encodingVersion != null && literalShape) {
                throw new IllegalArgumentException("SOAP encoded values do not support XML text or element wrappers");
            }
            if (encodingVersion != null && property.isAttribute()) {
                this.requireEncodedAttribute(property.getName(), encodingVersion);
            }
            if (!this.supportsXmlType(property.getJavaType(), encodingVersion, visited, mapping)) {
                return false;
            }
        }
        return true;
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

    private boolean supportsParts(List<SoapPart> parts, Type[] types, XmlMapper mapper) {
        for (int index = 0; index < parts.size(); index++) {
            SoapPart part = parts.get(index);
            JavaType javaType = mapper.constructType(types[index]);
            boolean repeated = part.maxOccurs() < 0 || part.maxOccurs() > 1;
            boolean repeatedType = javaType.isArrayType() || Collection.class.isAssignableFrom(javaType.getRawClass());
            if (repeated && !repeatedType) {
                return false;
            }
            if (part.minOccurs() == 0 && !repeated && javaType.isPrimitive()) {
                return false;
            }
        }
        return true;
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
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        SoapHeaderHandler headerHandler = context.metContext().getExtension(SoapHeaderHandler.class);
        XmlMapper template = context.metContext().getExtension(XmlMapper.class);
        SoapMessageCodec fallback = SoapMessageCodec.newFaults(config, context.routes(), //
                headerHandler, new SoapXmlMapping(SoapXmlMapping.configuredMapper(template)));
        // 缓存跟随已提交的端点绑定更新；编码器只持有定义，不反向持有作为弱键的绑定。
        // 在途请求独立持有原编码器，新请求在资源更新后取得新绑定对应的编码器。
        Map<ProtocolBinding, SoapMessageCodec> mounted = new WeakHashMap<>();
        Function<MetBindInfo<?>, SoapMessageCodec> codecs = service -> {
            ProtocolBinding binding = context.routes().binding(service);
            return mounted.computeIfAbsent(binding, accepted -> {
                SoapProtocolMeta definition = (SoapProtocolMeta) accepted.getDefinition();
                return SoapMessageCodec.newService(config, context.routes(), headerHandler, definition);
            });
        };
        return new SoapSession(config, context, connection, messages, fallback, codecs);
    }
}
