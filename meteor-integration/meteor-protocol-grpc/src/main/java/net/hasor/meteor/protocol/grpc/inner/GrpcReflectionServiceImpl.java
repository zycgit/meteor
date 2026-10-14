/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.inner;
import java.util.*;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** 标准 v1 查询，仅暴露本实例实际发布且绑定 Protobuf 描述的服务。 */
final class GrpcReflectionServiceImpl implements GrpcReflectionService {
    private final GrpcServicesExtension services;

    GrpcReflectionServiceImpl(GrpcServicesExtension services) {
        this.services = services;
    }

    @Override
    public Flow.Publisher<JsonNode> ServerReflectionInfo(Flow.Publisher<JsonNode> requests) {
        return subscriber -> requests.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(JsonNode request) {
                subscriber.onNext(GrpcReflectionServiceImpl.this.respond(request));
            }

            @Override
            public void onError(Throwable failure) {
                subscriber.onError(failure);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    private JsonNode respond(JsonNode request) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("validHost", request.path("host").asText(""));
        response.set("originalRequest", request);
        try {
            // 每次查询读取已发布服务，动态发布和撤销不依赖另外一份服务登记表。
            Map<String, ServiceDescriptor> published = this.descriptors();
            Map<String, FileDescriptor> files = new LinkedHashMap<>();
            for (ServiceDescriptor service : published.values()) {
                this.collect(service.getFile(), files);
            }
            if (request.has("listServices")) {
                ArrayNode names = response.putObject("listServicesResponse").putArray("service");
                for (String name : published.keySet()) {
                    names.addObject().put("name", name);
                }
                return response;
            }
            if (request.has("allExtensionNumbersOfType")) {
                this.extensions(request.get("allExtensionNumbersOfType").asText(), files, response);
                return response;
            }

            FileDescriptor found = null;
            if (request.has("fileByFilename")) {
                found = files.get(request.get("fileByFilename").asText());
            } else if (request.has("fileContainingSymbol")) {
                String symbol = request.get("fileContainingSymbol").asText();
                found = this.symbol(symbol, files);
            } else if (request.has("fileContainingExtension")) {
                JsonNode extension = request.get("fileContainingExtension");
                String type = extension.path("containingType").asText();
                int number = extension.path("extensionNumber").asInt();
                found = this.extension(type, number, files);
            } else {
                return this.error(response, 3, "Missing reflection query");
            }
            if (found == null) {
                return this.error(response, 5, "Descriptor not found");
            }

            // 返回根文件及所有依赖，不向客户端返回 Java 类型或内部服务对象。
            Map<String, FileDescriptor> dependencies = new LinkedHashMap<>();
            this.collect(found, dependencies);
            ArrayNode output = response.putObject("fileDescriptorResponse").putArray("fileDescriptorProto");
            for (FileDescriptor file : dependencies.values()) {
                output.add(file.toProto().toByteArray());
            }
            return response;
        } catch (IllegalStateException failure) {
            return this.error(response, 13, failure.getMessage());
        }
    }

    private Map<String, ServiceDescriptor> descriptors() {
        Map<String, ServiceDescriptor> result = new TreeMap<>();
        for (MetBindInfo<?> info : this.services.published()) {
            if (!(info.getBindResource() instanceof GrpcProtoResource resource)) {
                continue;
            }
            String coding = info.getSerializeType();
            if (!StringUtils.isBlank(coding) && !StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
                continue;
            }
            String name = info.getAliasName("grpc");
            ServiceDescriptor descriptor = resource.service(name);
            if (descriptor != null) {
                result.put(name, descriptor);
            }
        }
        return result;
    }

    private void collect(FileDescriptor file, Map<String, FileDescriptor> output) {
        FileDescriptor previous = output.putIfAbsent(file.getName(), file);
        if (previous != null) {
            if (!previous.toProto().equals(file.toProto())) {
                throw new IllegalStateException("Conflicting proto file: " + file.getName());
            }
            return;
        }
        for (FileDescriptor dependency : file.getDependencies()) {
            this.collect(dependency, output);
        }
    }

    private FileDescriptor symbol(String name, Map<String, FileDescriptor> files) {
        for (FileDescriptor file : files.values()) {
            for (ServiceDescriptor service : file.getServices()) {
                if (StringUtils.equals(name, service.getFullName())) {
                    return file;
                }
                for (var method : service.getMethods()) {
                    if (StringUtils.equals(name, method.getFullName())) {
                        return file;
                    }
                }
            }
            for (var type : file.getEnumTypes()) {
                if (StringUtils.equals(name, type.getFullName())) {
                    return file;
                }
                for (var value : type.getValues()) {
                    if (StringUtils.equals(name, value.getFullName())) {
                        return file;
                    }
                }
            }
            for (FieldDescriptor field : file.getExtensions()) {
                if (StringUtils.equals(name, field.getFullName())) {
                    return file;
                }
            }
            for (Descriptor type : this.messages(file)) {
                if (StringUtils.equals(name, type.getFullName())) {
                    return file;
                }
                for (FieldDescriptor field : type.getFields()) {
                    if (StringUtils.equals(name, field.getFullName())) {
                        return file;
                    }
                }
                for (var oneof : type.getOneofs()) {
                    if (StringUtils.equals(name, oneof.getFullName())) {
                        return file;
                    }
                }
                for (var enumeration : type.getEnumTypes()) {
                    if (StringUtils.equals(name, enumeration.getFullName())) {
                        return file;
                    }
                    for (var value : enumeration.getValues()) {
                        if (StringUtils.equals(name, value.getFullName())) {
                            return file;
                        }
                    }
                }
                for (FieldDescriptor field : type.getExtensions()) {
                    if (StringUtils.equals(name, field.getFullName())) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private List<Descriptor> messages(FileDescriptor file) {
        List<Descriptor> messages = new ArrayList<>(file.getMessageTypes());
        for (int index = 0; index < messages.size(); index++) {
            messages.addAll(messages.get(index).getNestedTypes());
        }
        return messages;
    }

    private List<FieldDescriptor> extensionFields(FileDescriptor file) {
        List<FieldDescriptor> result = new ArrayList<>(file.getExtensions());
        for (Descriptor message : this.messages(file)) {
            result.addAll(message.getExtensions());
        }
        return result;
    }

    private FileDescriptor extension(String type, int number, Map<String, FileDescriptor> files) {
        for (FileDescriptor file : files.values()) {
            for (FieldDescriptor field : this.extensionFields(file)) {
                String owner = field.getContainingType().getFullName();
                if (field.getNumber() == number && StringUtils.equals(type, owner)) {
                    return file;
                }
            }
        }
        return null;
    }

    private void extensions(String type, Map<String, FileDescriptor> files, ObjectNode response) {
        boolean known = false;
        List<Integer> numbers = new ArrayList<>();
        for (FileDescriptor file : files.values()) {
            for (Descriptor message : this.messages(file)) {
                known |= StringUtils.equals(type, message.getFullName());
            }
            for (FieldDescriptor field : this.extensionFields(file)) {
                if (StringUtils.equals(type, field.getContainingType().getFullName())) {
                    numbers.add(field.getNumber());
                }
            }
        }
        if (!known) {
            this.error(response, 5, "Message type not found: " + type);
            return;
        }
        ObjectNode result = response.putObject("allExtensionNumbersResponse");
        result.put("baseTypeName", type);
        ArrayNode extensions = result.putArray("extensionNumber");
        numbers.stream().distinct().sorted().forEach(extensions::add);
    }

    private JsonNode error(ObjectNode response, int code, String message) {
        ObjectNode error = response.putObject("errorResponse");
        error.put("errorCode", code);
        error.put("errorMessage", message);
        return response;
    }
}
