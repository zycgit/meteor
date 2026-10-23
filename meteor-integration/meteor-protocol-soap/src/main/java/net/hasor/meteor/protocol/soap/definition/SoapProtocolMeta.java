/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.ParameterMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.soap.mapping.SoapMethodTypes;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;

/** 一个服务在一个接入点上准备的 SOAP 契约与 XML 映射配置。 */
public final class SoapProtocolMeta {
    private final SoapDefinitionResource       resource;
    private final XmlMapper                    mapper;
    private final SoapXmlMapping               mapping;
    private final Map<String, Method>          methods        = new LinkedHashMap<>();
    private final Map<String, SoapOperation>   operations     = new LinkedHashMap<>();
    private final Map<Method, SoapMethodTypes> signatures     = new LinkedHashMap<>();
    private final Map<Method, int[]>           parameterOrder = new LinkedHashMap<>();

    public SoapProtocolMeta(SoapDefinitionResource resource, SoapXmlMapping mapping, MetBindInfo<?> service) {
        this.resource = resource;
        this.mapper = mapping.mapper();
        this.mapping = mapping;
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            String remoteName = MetRuntimeUtils.getProtocolMethodName(method);
            SoapOperation operation = resource.definition().operation(remoteName);
            this.methods.put(remoteName, method);
            this.operations.put(method.getName(), operation);
            SoapMethodTypes types = new SoapMethodTypes(this.mapper, service.getBindType(), method);
            this.signatures.put(method, types);
            for (JavaType parameter : types.getParameterTypes()) {
                this.mapping.prepare(parameter);
            }

            if (method.getReturnType() != void.class) {
                this.mapping.prepare(types.getReturnType());
            }
            this.parameterOrder.put(method, this.bindParameters(method, operation));

            String result = ServiceMetaParser.method(method).getReturnName();
            if (result != null && !operation.oneWay()) {
                List<SoapPart> parts = operation.outputBody().parts();
                if (parts.size() != 1 || !matches(parts.get(0), result)) {
                    throw new IllegalArgumentException("MetReturn does not select the SOAP output part: " + method);
                }
            }
        }
    }

    /** 发布阶段生成或绑定的契约。 */
    public SoapDefinitionResource resource() {
        return this.resource;
    }

    /** 导出实际 WSDL 操作、参数顺序和已编译 XML 映射，保留当前端点的资源版本。 */
    public ObjectNode describeContract(ObjectNode description) {
        SoapDefinition definition = this.resource.definition();
        description.put("version", definition.version().displayName());
        description.put("namespace", definition.targetNamespace());
        description.set("types", this.mapping.describeTypes());
        description.set("schemas", this.resource.describeSchemas());
        ArrayNode operations = description.putArray("methods");
        for (Map.Entry<String, Method> entry : this.methods.entrySet()) {
            Method method = entry.getValue();
            SoapOperation operation = definition.operation(entry.getKey());
            ObjectNode target = operations.addObject();
            target.put("javaSignature", ServiceMetaParser.method(method).getJavaSignature());
            target.put("name", operation.name());
            target.put("message", operation.oneWay());
            target.put("soapAction", operation.soapAction());
            target.put("style", operation.style());
            target.put("use", operation.inputBody().use());
            if (operation.inputBody().wrapper() != null) {
                target.put("requestWrapper", operation.inputBody().wrapper().toString());
            }

            ArrayNode parameters = target.putArray("parameters");
            List<SoapPart> parts = operation.inputBody().parts();
            int[] order = this.parameterOrder.get(method);
            for (int index = 0; index < parts.size(); index++) {
                ObjectNode parameter = parameters.addObject();
                parameter.put("argIndex", order.length == 1 ? order[0] : order[index]);
                describePart(parts.get(index), parameter);
            }

            if (!operation.oneWay()) {
                SoapBody output = operation.outputBody();
                if (output.wrapper() != null) {
                    target.put("responseWrapper", output.wrapper().toString());
                }
                ArrayNode returns = target.putArray("returns");
                for (SoapPart part : output.parts()) {
                    describePart(part, returns.addObject());
                }
            }

            ObjectNode faults = target.putObject("faults");
            for (Map.Entry<String, SoapFaultBinding> fault : operation.faults().entrySet()) {
                describePart(fault.getValue().part(), faults.putObject(fault.getKey()));
            }
        }
        return description;
    }

    private static void describePart(SoapPart part, ObjectNode description) {
        description.put("name", part.name());
        description.put("element", part.element() == null ? null : part.element().toString());
        description.put("type", part.type() == null ? null : part.type().toString());
        description.put("minOccurs", part.minOccurs());
        description.put("maxOccurs", part.maxOccurs());
    }

    /** 与本接入点契约共同准备的 XML 映射配置。 */
    public XmlMapper mapper() {
        return this.mapper;
    }

    public SoapXmlMapping mapping() {
        return this.mapping;
    }

    public Method method(String name) {
        Method method = this.methods.get(name);
        if (method == null) {
            throw new IllegalArgumentException("Java method not found for WSDL operation: " + name);
        }
        return method;
    }

    public SoapOperation operation(String javaName) {
        SoapOperation operation = this.operations.get(javaName);
        if (operation == null) {
            throw new IllegalArgumentException("WSDL operation not found for Java method: " + javaName);
        }
        return operation;
    }

    public SoapMethodTypes types(Method method) {
        return this.signatures.get(method);
    }

    public JavaType[] wireParameterTypes(Method method) {
        JavaType[] types = this.signatures.get(method).getParameterTypes();
        int[] order = this.parameterOrder.get(method);
        JavaType[] result = new JavaType[order.length];
        for (int index = 0; index < order.length; index++) {
            result[index] = types[order[index]];
        }
        return result;
    }

    public Object[] wireParameters(Method method, Object[] values) {
        int[] order = this.parameterOrder.get(method);
        Object[] result = new Object[order.length];
        for (int index = 0; index < order.length; index++) {
            result[index] = values[order[index]];
        }
        return result;
    }

    public Object[] javaParameters(Method method, Object[] values) {
        int[] order = this.parameterOrder.get(method);
        Object[] result = new Object[order.length];
        for (int index = 0; index < order.length; index++) {
            result[order[index]] = values[index];
        }
        return result;
    }

    private int[] bindParameters(Method method, SoapOperation operation) {
        Parameter[] parameters = method.getParameters();
        int[] order = new int[parameters.length];
        Arrays.fill(order, -1);
        List<SoapPart> parts = operation.inputBody().parts();
        for (int index = 0; index < parameters.length; index++) {
            ParameterMeta annotation = ServiceMetaParser.parameter(parameters[index]);
            int selected = index;
            if (!annotation.getDeclaredName().isEmpty()) {
                selected = -1;
                for (int target = 0; target < parts.size(); target++) {
                    if (matches(parts.get(target), annotation.getDeclaredName())) {
                        if (selected != -1) {
                            String message = "Ambiguous SOAP parameter: " + annotation.getDeclaredName();
                            throw new IllegalArgumentException(message);
                        }
                        selected = target;
                    }
                }
            }

            if (selected < 0 || selected >= order.length || order[selected] != -1) {
                throw new IllegalArgumentException("MetParam does not select a unique SOAP input part: " + method);
            }
            order[selected] = index;
        }
        return order;
    }

    private static boolean matches(SoapPart part, String name) {
        return StringUtils.equals(part.name(), name) //
                || part.element() != null && StringUtils.equals(part.element().getLocalPart(), name);
    }
}
