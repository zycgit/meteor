/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.oncrpc.codec.*;

/** Converts ONC RPC contracts to Caller/Dispatcher payloads; never invokes a business implementation. */
public final class OncRpcMessageMapper {
    private final RouteMountIndex routes;
    private final MetContext      context;
    private final int             maxMessageSize;
    private final int             maxContainerSize;
    private final int             maxDepth;
    private final String          auth;
    private final OncRpcAuth      credentials;

    public OncRpcMessageMapper(ProtocolConfig protocol, RouteMountIndex routes, MetContext context) {
        this.routes = routes;
        this.context = context;
        this.maxMessageSize = protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        this.maxContainerSize = protocol.integer("maxContainerSize", 100000);
        this.maxDepth = protocol.integer("maxDepth", 64);
        this.auth = protocol.option("auth", "none");
        this.credentials = credentials(protocol);
    }

    public OncRpcMessage encodeRequest(RequestPayload request, long xid) {
        validateSerialization(request.getSerializeType());
        if (request.isMessage()) {
            throw new IllegalArgumentException("ONC RPC does not support Meteor message mode");
        }
        MetBindInfo<?> service = this.service(request);
        OncRpcServiceBinding resource = this.resource(service);
        Method method = method(resource, request.getTargetMethod());
        List<Object> values = request.getParameterValues();
        Type[] types = method.getGenericParameterTypes();
        if (values.size() != types.length) {
            throw new IllegalArgumentException("ONC RPC argument count differs from the service contract");
        }

        // Procedure arguments form one XDR struct in Java declaration order, without an extra argument count.
        XdrOutput output = this.output();
        for (int index = 0; index < types.length; index++) {
            XdrCodec<Object> codec = resource.parameter(method.getName(), index);
            output.write(codec, values.get(resource.parameterIndex(method.getName(), index)));
        }
        long procedure = resource.getProcedure(method.getName());
        return OncRpcMessage.newCall(xid, 2, resource.getProgram(), resource.getVersion(), procedure, //
                this.credentials, OncRpcAuth.newNone(), output.toByteArray());
    }

    /** A validated NULL procedure returns null and is answered by the session without entering the Dispatcher. */
    public RequestPayload decodeRequest(OncRpcMessage message) {
        if (!message.isCall()) {
            throw new IllegalArgumentException("Expected an ONC RPC CALL");
        }
        if (message.getRpcVersion() != 2) {
            throw OncRpcException.newRpcMismatch(2, 2);
        }
        this.validateAuth(message);

        // Program and version select the published contract, independently of Java names and method names.
        MetBindInfo<?> selected = null;
        OncRpcServiceBinding selectedResource = null;
        long low = 0xffffffffL;
        long high = -1;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> candidate = this.context.getServiceInfo(id);
            boolean provider = candidate != null && candidate.getServiceType() == MetServiceType.Provider;
            if (!provider || candidate.isShadow() || !this.routes.accepts(candidate)) {
                continue;
            }
            OncRpcServiceBinding resource = this.resource(candidate);
            if (resource.getProgram() != message.getProgram()) {
                continue;
            }
            low = Math.min(low, resource.getVersion());
            high = Math.max(high, resource.getVersion());
            if (resource.getVersion() == message.getVersion()) {
                if (selected != null) {
                    throw error(OncRpcMessage.SYSTEM_ERR, "Ambiguous ONC RPC program and version");
                }
                selected = candidate;
                selectedResource = resource;
            }
        }
        if (selected == null) {
            if (high >= 0) {
                throw OncRpcException.newProgramMismatch(low, high);
            }
            throw error(OncRpcMessage.PROG_UNAVAIL, "ONC RPC program is unavailable");
        }
        if (message.getProcedure() == 0) {
            if (message.getBody().length != 0) {
                throw error(OncRpcMessage.GARBAGE_ARGS, "NULL procedure requires void arguments");
            }
            return null;
        }
        Method contract = selectedResource.getMethods().get(message.getProcedure());
        if (contract == null) {
            throw error(OncRpcMessage.PROC_UNAVAIL, "ONC RPC procedure is unavailable");
        }

        // Only the argument conversion happens here; normal execution remains in Meteor's Dispatcher.
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(selected.getBindGroup());
        request.setServiceName(selected.getBindName());
        request.setServiceVersion(selected.getBindVersion());
        request.setTargetMethod(contract.getName());
        request.setSerializeType("oncrpc");
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        this.addIdentity(request, message.getCredentials());
        Type[] types = contract.getGenericParameterTypes();
        Class<?>[] rawTypes = contract.getParameterTypes();
        try {
            XdrInput input = this.input(message.getBody());
            Object[] values = new Object[types.length];
            for (int index = 0; index < types.length; index++) {
                Object value = input.read(selectedResource.parameter(contract.getName(), index));
                values[selectedResource.parameterIndex(contract.getName(), index)] = value;
            }
            input.requireEnd();
            for (int index = 0; index < values.length; index++) {
                request.addParameter(rawTypes[index].getName(), values[index]);
            }
        } catch (IllegalArgumentException malformed) {
            throw error(OncRpcMessage.GARBAGE_ARGS, "Invalid ONC RPC procedure arguments");
        }
        return request;
    }

    public OncRpcMessage encodeResponse(RequestPayload request, ResponsePayload response, OncRpcMessage original) {
        if (response.getStatus() != ProtocolStatus.OK || response.getFailure() != null) {
            return this.replyError(original, response.getFailure());
        }
        try {
            OncRpcServiceBinding resource = this.resource(this.service(request));
            Method method = method(resource, request.getTargetMethod());
            XdrOutput output = this.output();
            output.write(resource.result(method.getName()), response.getReturnData());
            return OncRpcMessage.newSuccess(original.getXid(), OncRpcAuth.newNone(), output.toByteArray());
        } catch (RuntimeException invalidResult) {
            return this.replyError(original, invalidResult);
        }
    }

    public ResponsePayload decodeResponse(RequestPayload request, OncRpcMessage message) {
        if (message.getMessageType() != OncRpcMessage.REPLY) {
            throw new IllegalArgumentException("Expected an ONC RPC REPLY");
        }
        if (message.getReplyStatus() == OncRpcMessage.MSG_DENIED) {
            if (message.getRejectStatus() == OncRpcMessage.RPC_MISMATCH) {
                throw OncRpcException.newRpcMismatch(message.getLowVersion(), message.getHighVersion());
            }
            throw OncRpcException.newAuthError(message.getAuthStatus(), "ONC RPC authentication was rejected");
        }
        int verifierStatus = message.getVerifier().validateVerifier();
        if (verifierStatus != OncRpcAuth.AUTH_OK) {
            throw OncRpcException.newAuthError(OncRpcAuth.AUTH_INVALIDRESP, "Invalid ONC RPC reply verifier");
        }
        if (!message.isSuccess()) {
            int status = message.getAcceptStatus();
            long low = message.getLowVersion();
            long high = message.getHighVersion();
            throw OncRpcException.newAccepted(status, low, high, "ONC RPC procedure returned a standard error");
        }
        OncRpcServiceBinding resource = this.resource(this.service(request));
        Method method = method(resource, request.getTargetMethod());
        XdrInput input = this.input(message.getBody());
        Object value = input.read(resource.result(method.getName()));
        input.requireEnd();
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setReceiveTime(System.currentTimeMillis());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("oncrpc");
        response.setReturnType(method.getReturnType().getName());
        response.setReturnData(value);
        return response;
    }

    public OncRpcMessage replyError(OncRpcMessage original, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            cause = invocation.getCause();
        }
        if (cause instanceof OncRpcException standard) {
            return standard.toReply(original.getXid());
        }
        // RFC 5531 SYSTEM_ERR has a void error arm; application exception objects are never serialized.
        return OncRpcMessage.newAcceptedError(original.getXid(), OncRpcAuth.newNone(), OncRpcMessage.SYSTEM_ERR, 0, 0);
    }

    public static OncRpcServiceBinding prepareServiceDefinition(ProtocolConfig protocol, ProtocolMeta meta) {
        MetBindInfo<?> service = meta.getService();
        // 端点配置错误不能当作接口不支持处理。
        credentials(protocol);
        int maxBytes = protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        int maxElements = protocol.integer("maxContainerSize", 100000);
        protocol.integer("maxDepth", 64);
        Object bound = meta.getBindResource();
        boolean known = bound instanceof OncRpcServiceResource || bound instanceof OncRpcIdlResource;
        if (bound != null && !known) {
            throw new IllegalArgumentException("ONC RPC requires an OncRpcServiceResource or OncRpcIdlResource");
        }
        String serializer = service.getSerializeType();
        if (serializer != null && !StringUtils.equalsIgnoreCase(serializer, "oncrpc")) {
            return null;
        }
        if (service.isMessage()) {
            return null;
        }

        // 只准备接口的静态契约，不在发布阶段创建会话或运行期缓存。
        Object resource;
        if (known) {
            resource = bound;
        } else {
            resource = OncRpcServiceResource.infer(service.getBindType());
        }
        if (resource == null) {
            return null;
        }

        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (ServiceMetaParser.method(method).isMessage()) {
                return null;
            }
            if (resource instanceof OncRpcServiceResource annotated) {
                Method contract = annotated.getMethod(method.getName());
                if (contract == null) {
                    return null;
                }
                boolean parameters = Arrays.equals(method.getGenericParameterTypes(), contract.getGenericParameterTypes());
                boolean result = method.getGenericReturnType().equals(contract.getGenericReturnType());
                if (!parameters || !result) {
                    return null;
                }
            }
        }
        try {
            return new OncRpcServiceBinding(resource, service, protocol);
        } catch (UnsupportedOperationException unsupported) {
            return null;
        }
    }

    private void validateAuth(OncRpcMessage message) {
        int credential = message.getCredentials().validateCredential();
        if (credential != OncRpcAuth.AUTH_OK) {
            throw OncRpcException.newAuthError(credential, "Invalid ONC RPC credentials");
        }
        int verifier = message.getVerifier().validateVerifier();
        if (verifier != OncRpcAuth.AUTH_OK) {
            throw OncRpcException.newAuthError(verifier, "Invalid ONC RPC request verifier");
        }
        boolean requireSystem = StringUtils.equals(this.auth, "require-sys");
        boolean hasSystemIdentity = message.getCredentials().getFlavor() == OncRpcAuth.AUTH_SYS;
        if (requireSystem && message.getProcedure() != 0 && !hasSystemIdentity) {
            throw OncRpcException.newAuthError(OncRpcAuth.AUTH_TOOWEAK, "AUTH_SYS credentials are required");
        }
    }

    private void addIdentity(RequestPayload request, OncRpcAuth auth) {
        request.addOption("oncrpc.auth.flavor", Integer.toString(auth.getFlavor()));
        if (auth.getFlavor() != OncRpcAuth.AUTH_SYS) {
            return;
        }
        // AUTH_SYS is the peer's self-declared identity, not a trusted authentication principal.
        Map<String, Object> identity = auth.getSystemIdentity();
        request.addOption("oncrpc.auth.machine", (String) identity.get("machineName"));
        request.addOption("oncrpc.auth.uid", identity.get("uid").toString());
        request.addOption("oncrpc.auth.gid", identity.get("gid").toString());
        request.addOption("oncrpc.auth.stamp", identity.get("stamp").toString());
        @SuppressWarnings("unchecked") List<Long> groups = (List<Long>) identity.get("groups");
        List<String> encoded = new ArrayList<>(groups.size());
        for (Long group : groups) {
            encoded.add(group.toString());
        }
        request.addOption("oncrpc.auth.groups", String.join(",", encoded));
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        String version = request.getServiceVersion();
        MetBindInfo<?> service = this.context.getServiceInfo(group, name, version);
        if (service == null || !this.routes.accepts(service)) {
            throw new IllegalArgumentException("ONC RPC service is unavailable");
        }
        return service;
    }

    private OncRpcServiceBinding resource(MetBindInfo<?> service) {
        ProtocolBinding binding = this.routes.binding(service);
        if (binding != null && binding.getDefinition() instanceof OncRpcServiceBinding resource) {
            return resource;
        }
        throw new IllegalArgumentException("ONC RPC service has no prepared protocol contract");
    }

    private static Method method(OncRpcServiceBinding resource, String name) {
        Method method = resource.getMethod(name);
        if (method == null) {
            throw error(OncRpcMessage.PROC_UNAVAIL, "ONC RPC procedure is unavailable: " + name);
        }
        return method;
    }

    private static void validateSerialization(String serialization) {
        if (serialization != null && !StringUtils.equalsIgnoreCase(serialization, "oncrpc")) {
            throw new IllegalArgumentException("ONC RPC requires XDR; bind XdrCodec for custom Java types");
        }
    }

    private XdrInput input(byte[] body) {
        return new XdrInput(body, this.maxMessageSize, this.maxContainerSize, this.maxDepth);
    }

    private XdrOutput output() {
        return new XdrOutput(this.maxMessageSize, this.maxContainerSize, this.maxDepth);
    }

    private static OncRpcAuth credentials(ProtocolConfig protocol) {
        String auth = protocol.option("auth", "none");
        if (StringUtils.equals(auth, "none")) {
            return OncRpcAuth.newNone();
        }
        if (!StringUtils.equals(auth, "sys") && !StringUtils.equals(auth, "require-sys")) {
            throw new IllegalArgumentException("ONC RPC auth must be none, sys or require-sys");
        }
        long stamp = unsignedOption(protocol, "authStamp", 0);
        long uid = unsignedOption(protocol, "authUid", 0);
        long gid = unsignedOption(protocol, "authGid", 0);
        String machine = protocol.option("authMachine", "meteor");
        String groupsOption = protocol.option("authGroups", "");
        long[] groups = new long[0];
        if (!StringUtils.isBlank(groupsOption)) {
            String[] values = groupsOption.split(",", -1);
            groups = new long[values.length];
            for (int index = 0; index < values.length; index++) {
                groups[index] = Long.parseLong(values[index].trim());
            }
        }
        return OncRpcAuth.newSystem(stamp, machine, uid, gid, groups);
    }

    private static long unsignedOption(ProtocolConfig protocol, String name, long fallback) {
        long value = Long.parseLong(protocol.option(name, Long.toString(fallback)));
        if (value < 0 || value > 0xffffffffL) {
            throw new IllegalArgumentException(name + " must be an unsigned 32-bit integer");
        }
        return value;
    }

    private static OncRpcException error(int status, String message) {
        return OncRpcException.newAcceptedError(status, message);
    }
}
