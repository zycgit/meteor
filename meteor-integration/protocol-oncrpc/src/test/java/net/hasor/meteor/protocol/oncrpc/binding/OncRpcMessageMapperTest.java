/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.annotation.*;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.oncrpc.OncRpcProtocol;
import net.hasor.meteor.protocol.oncrpc.codec.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OncRpcMessageMapperTest {
    @Test
    public void outgoingArgumentsUseTheirJavaDeclarationOrder() {
        // 两个参数使用不同数值，正文直接按声明顺序写入，不额外写参数个数。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "subtract", 19, 7);

        // 独立读取正文，避免用同一个 mapper 的往返掩盖顺序错误。
        OncRpcMessage call = mapper.encodeRequest(request, 37);
        XdrInput body = input(call.getBody());
        assertEquals(19, body.readInt());
        assertEquals(7, body.readInt());
        body.requireEnd();
        assertEquals(0x20000001L, call.getProgram());
        assertEquals(2, call.getVersion());
        assertEquals(1, call.getProcedure());
    }

    @Test
    public void outgoingXidRetainsAllUnsignedBits() {
        // 高位为一的线上事务号不会被转换为负数或截断。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessage call = mapper(config(), service).encodeRequest(request(service, "ping"), 0xffffffffL);
        assertEquals(0xffffffffL, call.getXid());
    }

    @Test
    public void outgoingRequestRejectsANonXdrSerializer() {
        // ONC RPC 的标准正文为 XDR，不能把普通 JSON 序列化结果当作过程参数。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        request.setSerializeType("json");
        OncRpcMessageMapper mapper = mapper(config(), service);
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void outgoingRequestAcceptsCaseInsensitiveXdrSerializerName() {
        // 协议专属序列化名称按现有框架约定忽略大小写。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        request.setSerializeType("OnCrPc");
        assertEquals(2, mapper(config(), service).encodeRequest(request, 1).getProcedure());
    }

    @Test
    public void outgoingMessageModeIsRejected() {
        // ONC RPC 的 void 过程仍有标准响应，不能当作 Meteor 的无响应消息模式。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        request.setMessage(true);
        OncRpcMessageMapper mapper = mapper(config(), service);
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void outgoingArgumentCountMustMatchTheProcedure() {
        // 缺少声明参数时拒绝写出一个无法按契约解析的正文。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "subtract", 19);
        OncRpcMessageMapper mapper = mapper(config(), service);
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void outgoingUnknownMethodReportsProcedureUnavailable() {
        // 本地 Java 方法名必须能对应到一个明确过程号。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "absent");
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.encodeRequest(request, 1));
        assertEquals(OncRpcMessage.PROC_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void outgoingMissingServiceIsRejected() {
        // 消费者绑定不存在时不猜测程序号。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config());
        RequestPayload request = request(service, "ping");
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void outgoingServiceOnAnotherProtocolIsRejected() {
        // 即使本地能查询到服务，也必须属于本协议挂载。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.addBindProtocol("rsf");
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "ping");
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void incomingArgumentsPopulateDispatcherTypesAndValues() {
        // 从独立构造的标准 XDR 正文恢复参数及服务标识。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        XdrOutput body = output();
        body.writeInt(19);
        body.writeInt(7);
        OncRpcMessageMapper mapper = mapper(config(), service);

        // 解码只构造 Dispatcher 请求，不调用业务实现。
        RequestPayload request = mapper.decodeRequest(call(1, body.toByteArray()));
        assertEquals(List.of("int", "int"), request.getParameterTypes());
        assertEquals(List.of(19, 7), request.getParameterValues());
        assertEquals(service.getBindGroup(), request.getServiceGroup());
        assertEquals(service.getBindName(), request.getServiceName());
        assertEquals(service.getBindVersion(), request.getServiceVersion());
        assertEquals("subtract", request.getTargetMethod());
        assertEquals("oncrpc", request.getSerializeType());
        assertEquals(3400, request.getClientTimeout());
        assertTrue(request.getReceiveTime() > 0);
    }

    @Test
    public void incomingProgramAndVersionSelectThePublishedContract() {
        // 同一程序可发布多个版本，Java 服务名不参与线上选择。
        ServiceDomain<VersionOne> versionOne = service(VersionOne.class);
        ServiceDomain<VersionThree> versionThree = service(VersionThree.class);
        OncRpcMessageMapper mapper = mapper(config(), versionOne, versionThree);
        OncRpcMessage call = call(0x20000001L, 3, 2, OncRpcAuth.newNone(), OncRpcAuth.newNone(), new byte[0]);

        // 目标版本对应自己的服务声明。
        RequestPayload request = mapper.decodeRequest(call);
        assertEquals(versionThree.getBindName(), request.getServiceName());
        assertEquals("ping", request.getTargetMethod());
    }

    @Test
    public void incomingUnknownProgramReportsProgramUnavailable() {
        // 程序号不匹配时返回标准 PROG_UNAVAIL。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcMessage call = call(0x20000002L, 2, 2, OncRpcAuth.newNone(), OncRpcAuth.newNone(), new byte[0]);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingUnknownVersionReportsThePublishedRange() {
        // 版本不匹配时以该程序实际发布的最低、最高版本构造标准错误。
        OncRpcMessageMapper mapper = mapper(config(), service(VersionOne.class), service(VersionThree.class));
        OncRpcMessage call = call(0x20000001L, 7, 2, OncRpcAuth.newNone(), OncRpcAuth.newNone(), new byte[0]);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call));
        assertEquals(OncRpcMessage.PROG_MISMATCH, failure.getAcceptStatus());
        assertEquals(1, failure.getLowVersion());
        assertEquals(3, failure.getHighVersion());
    }

    @Test
    public void incomingZeroVersionReportsThePublishedRange() {
        // 对端发送的版本零仍可解析，但不能匹配任何已发布程序版本。
        OncRpcMessageMapper mapper = mapper(config(), service(VersionOne.class), service(VersionThree.class));
        OncRpcMessage call = call(0x20000001L, 0, 2, OncRpcAuth.newNone(), OncRpcAuth.newNone(), new byte[0]);

        // 保留标准版本协商错误和有效范围，不把无效版本当成畸形 XDR。
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call));
        assertEquals(OncRpcMessage.PROG_MISMATCH, failure.getAcceptStatus());
        assertEquals(1, failure.getLowVersion());
        assertEquals(3, failure.getHighVersion());
    }

    @Test
    public void incomingConsumerDeclarationsAreNotProviders() {
        // 仅订阅的契约不能被远端当作已发布服务调用。
        ServiceDomain<Arithmetic> consumer = service(Arithmetic.class);
        consumer.setServiceType(MetServiceType.Consumer);
        OncRpcMessageMapper mapper = mapper(config(), consumer);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingShadowServicesAreNotExposed() {
        // 隐藏服务不会因为程序号匹配而变成网络提供者。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.setShadow(true);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingOtherProtocolServicesAreNotExposed() {
        // 协议选择过滤发生在程序号匹配之前。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.addBindProtocol("rsf");
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingServiceRejectedByThisEndpointIsNotExposed() {
        // 服务声明了 ONC RPC，但只被另一个端点接受。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcServiceResource resource = new OncRpcServiceResource(Arithmetic.class);
        resource.setVersion(2);
        OncRpcServiceBinding definition = new OncRpcServiceBinding(resource, service, config());
        ProtocolBinding binding = new ProtocolBinding(null, definition, null);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of("another-endpoint", binding));
        service.replaceProtocolPublication(Map.of("oncrpc", meta));

        // 当前端点不能仅凭程序号和协议名选择该服务。
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingDuplicateProgramVersionReportsSystemError() {
        // 两个提供者使用同一个程序号和版本时，不按容器遍历顺序选取。
        ServiceDomain<Arithmetic> first = service(Arithmetic.class);
        ServiceDomain<Arithmetic> second = service(Arithmetic.class);
        second.setBindName("duplicate");
        OncRpcMessageMapper mapper = mapper(config(), first, second);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.SYSTEM_ERR, failure.getAcceptStatus());
    }

    @Test
    public void incomingNullProcedureReturnsWithoutDispatcherRequest() {
        // 已发布程序的 NULL 过程由会话直接应答，不创建业务请求。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        assertNull(mapper.decodeRequest(call(0, new byte[0])));
    }

    @Test
    public void incomingNullProcedureStillRequiresAKnownProgram() {
        // NULL 不绕过程序号存在性检查。
        OncRpcMessageMapper mapper = mapper(config());
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(0, new byte[0])));
        assertEquals(OncRpcMessage.PROG_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingNullProcedureStillRequiresASupportedVersion() {
        // NULL 不绕过程序版本检查。
        OncRpcMessageMapper mapper = mapper(config(), service(VersionOne.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(0, new byte[0])));
        assertEquals(OncRpcMessage.PROG_MISMATCH, failure.getAcceptStatus());
    }

    @Test
    public void incomingNullProcedureRejectsTrailingArguments() {
        // NULL 的参数为 void，额外四字节不能被静默忽略。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(0, new byte[4])));
        assertEquals(OncRpcMessage.GARBAGE_ARGS, failure.getAcceptStatus());
    }

    @Test
    public void incomingUnknownProcedureReportsProcedureUnavailable() {
        // 已知程序和版本中的未定义过程号返回 PROC_UNAVAIL。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcMessage message = call(999, new byte[0]);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcMessage.PROC_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingProcedureNotOnPublishedFacadeIsUnavailable() {
        // 本地资源可以包含完整 IDL，发布的 Java 门面只暴露其中一部分。
        ServiceDomain<PingFacade> service = service(PingFacade.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(Arithmetic.class);
        resource.setVersion(2);
        service.setBindResource("oncrpc", resource);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(1, new byte[8])));
        assertEquals(OncRpcMessage.PROC_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingTruncatedArgumentsReportGarbageArguments() {
        // subtract 的两个整数必须同时存在。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(1, new byte[4])));
        assertEquals(OncRpcMessage.GARBAGE_ARGS, failure.getAcceptStatus());
    }

    @Test
    public void incomingTrailingArgumentsReportGarbageArguments() {
        // 正确参数之后的多余数据也属于无效正文。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcMessage message = call(1, new byte[12]);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcMessage.GARBAGE_ARGS, failure.getAcceptStatus());
    }

    @Test
    public void incomingReplyCannotBeDecodedAsACall() {
        // 方向错误的信封不会进入程序查找。
        OncRpcMessageMapper mapper = mapper(config());
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, OncRpcAuth.newNone(), new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> mapper.decodeRequest(reply));
    }

    @Test
    public void incomingWrongRpcVersionReportsDeniedMismatch() {
        // RPC 版本与业务程序版本分别校验。
        OncRpcMessageMapper mapper = mapper(config());
        OncRpcMessage call = OncRpcMessage.newCall(1, 3, 0x20000001L, 2, 0, //
                OncRpcAuth.newNone(), OncRpcAuth.newNone(), new byte[0]);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call));
        assertEquals(OncRpcMessage.MSG_DENIED, failure.getReplyStatus());
        assertEquals(OncRpcMessage.RPC_MISMATCH, failure.getRejectStatus());
        assertEquals(2, failure.getLowVersion());
        assertEquals(2, failure.getHighVersion());
    }

    @Test
    public void successfulVoidReplyHasAnEmptyBody() {
        // Java void 对应 XDR void，仍然返回 SUCCESS 信封。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = mapper.encodeResponse(request, success(null), call(2, new byte[0]));
        assertTrue(reply.isSuccess());
        assertEquals(0, reply.getBody().length);
        assertEquals(51, reply.getXid());
        assertEquals(OncRpcAuth.AUTH_NONE, reply.getVerifier().getFlavor());
    }

    @Test
    public void successfulIntegerReplyContainsOnlyTheXdrResult() {
        // 成功结果正文不附加 Java 类名或 Meteor 状态。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "subtract", 19, 7);
        OncRpcMessage reply = mapper.encodeResponse(request, success(12), call(1, new byte[8]));
        XdrInput body = input(reply.getBody());
        assertEquals(12, body.readInt());
        body.requireEnd();
    }

    @Test
    public void failedBusinessResultUsesSystemErrorWithoutExceptionBytes() {
        // 标准 SYSTEM_ERR 的 union 分支为 void，异常对象和异常消息不会泄漏到网络。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        ResponsePayload response = success(null);
        response.setFailure(new IllegalStateException("private application details"));
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage reply = mapper.encodeResponse(request(service, "ping"), response, call(2, new byte[0]));
        assertEquals(OncRpcMessage.SYSTEM_ERR, reply.getAcceptStatus());
        assertEquals(0, reply.getBody().length);
    }

    @Test
    public void nonSuccessMeteorStatusUsesSystemError() {
        // 没有 Throwable 的失败状态也不能被编码成成功响应。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        ResponsePayload response = success(null);
        response.setStatus(ProtocolStatus.QueueFull);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage reply = mapper.encodeResponse(request(service, "ping"), response, call(2, new byte[0]));
        assertEquals(OncRpcMessage.SYSTEM_ERR, reply.getAcceptStatus());
    }

    @Test
    public void malformedBusinessReturnValueUsesSystemError() {
        // 返回值无法满足已发布的 XDR 类型时以标准错误结束调用。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "subtract", 19, 7);
        OncRpcMessage reply = mapper.encodeResponse(request, success("not an integer"), call(1, new byte[8]));
        assertEquals(OncRpcMessage.SYSTEM_ERR, reply.getAcceptStatus());
        assertEquals(0, reply.getBody().length);
    }

    @Test
    public void replyErrorPreservesAnAcceptedProtocolFailure() {
        // 程序版本范围需要保留在 PROG_MISMATCH 的标准错误分支中。
        OncRpcMessageMapper mapper = mapper(config());
        OncRpcMessage reply = mapper.replyError(call(2, new byte[0]), OncRpcException.newProgramMismatch(1, 3));
        assertEquals(OncRpcMessage.PROG_MISMATCH, reply.getAcceptStatus());
        assertEquals(1, reply.getLowVersion());
        assertEquals(3, reply.getHighVersion());
    }

    @Test
    public void replyErrorUnwrapsInvocationTargetException() {
        // 反射包装不会抹掉明确的认证拒绝状态。
        OncRpcException cause = OncRpcException.newAuthError(OncRpcAuth.AUTH_TOOWEAK, "auth required");
        InvocationTargetException wrapper = new InvocationTargetException(cause);
        OncRpcMessage reply = mapper(config()).replyError(call(2, new byte[0]), wrapper);
        assertEquals(OncRpcMessage.MSG_DENIED, reply.getReplyStatus());
        assertEquals(OncRpcAuth.AUTH_TOOWEAK, reply.getAuthStatus());
    }

    @Test
    public void incomingSuccessReplyRestoresTheMeteorRequestId() {
        // 网络事务号由会话管理，响应恢复原始 Meteor 的长整型请求号。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "subtract", 19, 7);
        request.setRequestID(Long.MAX_VALUE);
        XdrOutput body = output();
        body.writeInt(12);
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, OncRpcAuth.newNone(), body.toByteArray());

        // 正常结果交回 Caller，保留原请求标识与类型。
        ResponsePayload response = mapper(config(), service).decodeResponse(request, reply);
        assertEquals(Long.MAX_VALUE, response.getRequestID());
        assertEquals(12, response.getReturnData());
        assertEquals("int", response.getReturnType());
        assertEquals(ProtocolStatus.OK, response.getStatus());
        assertEquals("oncrpc", response.getSerializeType());
        assertTrue(response.getReceiveTime() > 0);
    }

    @Test
    public void incomingVoidSuccessReplyDecodesToNull() {
        // void 过程成功不会被误判为缺少返回值。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, OncRpcAuth.newNone(), new byte[0]);
        ResponsePayload response = mapper(config(), service).decodeResponse(request, reply);
        assertNull(response.getReturnData());
        assertEquals("void", response.getReturnType());
    }

    @Test
    public void incomingCallCannotBeDecodedAsAReply() {
        // 回应入口只接受 REPLY 信封。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "ping");
        assertThrows(IllegalArgumentException.class, () -> mapper.decodeResponse(request, call(2, new byte[0])));
    }

    @Test
    public void incomingAcceptedErrorPropagatesItsStandardStatus() {
        // 远端的标准过程错误不会降为普通 Java 异常或成功空值。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newAcceptedError(1, OncRpcAuth.newNone(), //
                OncRpcMessage.PROC_UNAVAIL, 0, 0);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeResponse(request, reply));
        assertEquals(OncRpcMessage.PROC_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void incomingProgramMismatchReplyPreservesItsRange() {
        // 服务端报告的业务版本范围保留给调用方。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newAcceptedError(1, OncRpcAuth.newNone(), //
                OncRpcMessage.PROG_MISMATCH, 4, 9);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeResponse(request, reply));
        assertEquals(4, failure.getLowVersion());
        assertEquals(9, failure.getHighVersion());
    }

    @Test
    public void incomingDeniedRpcMismatchPreservesItsRange() {
        // RPC 协议版本拒绝属于 MSG_DENIED，与程序版本不匹配区分。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newRpcMismatch(1, 2, 2);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeResponse(request, reply));
        assertEquals(OncRpcMessage.MSG_DENIED, failure.getReplyStatus());
        assertEquals(OncRpcMessage.RPC_MISMATCH, failure.getRejectStatus());
        assertEquals(2, failure.getLowVersion());
    }

    @Test
    public void incomingDeniedAuthenticationPreservesItsStatus() {
        // AUTH_ERROR 的具体原因可被上层调用方读取。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newAuthError(1, OncRpcAuth.AUTH_TOOWEAK);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeResponse(request, reply));
        assertEquals(OncRpcAuth.AUTH_TOOWEAK, failure.getAuthStatus());
    }

    @Test
    public void incomingReplyRejectsUnsupportedVerifier() {
        // 认证 flavor 不能被当成无校验的成功响应接受。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcAuth verifier = new OncRpcAuth(99, new byte[0]);
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, verifier, new byte[0]);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeResponse(request, reply));
        assertEquals(OncRpcAuth.AUTH_INVALIDRESP, failure.getAuthStatus());
    }

    @Test
    public void incomingReplyRejectsTrailingResultBytes() {
        // void 成功正文后附加数据属于畸形响应。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, OncRpcAuth.newNone(), new byte[4]);
        OncRpcMessageMapper mapper = mapper(config(), service);
        assertThrows(IllegalArgumentException.class, () -> mapper.decodeResponse(request, reply));
    }

    @Test
    public void incomingAuthNoneIdentityIsExposedAsAnOption() {
        // 匿名认证只标明 flavor，不制造 UID 或认证主体。
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        RequestPayload request = mapper.decodeRequest(call(2, new byte[0]));
        assertEquals("0", request.getOption("oncrpc.auth.flavor"));
        assertNull(request.getOption("oncrpc.auth.uid"));
    }

    @Test
    public void incomingAuthSystemIdentityBecomesExplicitMetadata() {
        // AUTH_SYS 是对端自行声明的身份，各个数字保留无符号值。
        OncRpcAuth identity = OncRpcAuth.newSystem(0xffffffffL, "peer", 42, 7, new long[] { 8, 0xffffffffL });
        OncRpcMessage message = call(0x20000001L, 2, 2, identity, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));

        // 身份作为协议选项交付，不提升为受信任的认证主体。
        RequestPayload request = mapper.decodeRequest(message);
        assertEquals("1", request.getOption("oncrpc.auth.flavor"));
        assertEquals("peer", request.getOption("oncrpc.auth.machine"));
        assertEquals("42", request.getOption("oncrpc.auth.uid"));
        assertEquals("7", request.getOption("oncrpc.auth.gid"));
        assertEquals("4294967295", request.getOption("oncrpc.auth.stamp"));
        assertEquals("8,4294967295", request.getOption("oncrpc.auth.groups"));
    }

    @Test
    public void incomingUnsupportedCredentialsAreRejected() {
        // 未支持的认证机制通过标准 AUTH_REJECTEDCRED 返回。
        OncRpcAuth credentials = new OncRpcAuth(99, new byte[0]);
        OncRpcMessage message = call(0x20000001L, 2, 2, credentials, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcAuth.AUTH_REJECTEDCRED, failure.getAuthStatus());
    }

    @Test
    public void incomingMalformedSystemCredentialsAreRejected() {
        // 截断的 AUTH_SYS 正文不会进入业务参数读取。
        OncRpcAuth credentials = new OncRpcAuth(OncRpcAuth.AUTH_SYS, new byte[4]);
        OncRpcMessage message = call(0x20000001L, 2, 2, credentials, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcAuth.AUTH_BADCRED, failure.getAuthStatus());
    }

    @Test
    public void incomingUnsupportedRequestVerifierIsRejected() {
        // 请求验证器同样受认证机制约束。
        OncRpcAuth verifier = new OncRpcAuth(99, new byte[0]);
        OncRpcMessage message = call(0x20000001L, 2, 2, OncRpcAuth.newNone(), verifier, new byte[0]);
        OncRpcMessageMapper mapper = mapper(config(), service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcAuth.AUTH_REJECTEDVERF, failure.getAuthStatus());
    }

    @Test
    public void requireSystemAuthenticationRejectsAnonymousBusinessCalls() {
        // require-sys 对业务过程要求 AUTH_SYS。
        ProtocolConfig protocol = config(Map.of("auth", "require-sys"));
        OncRpcMessageMapper mapper = mapper(protocol, service(Arithmetic.class));
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcAuth.AUTH_TOOWEAK, failure.getAuthStatus());
    }

    @Test
    public void requireSystemAuthenticationAllowsAnonymousNullCalls() {
        // 标准 NULL 探测保持可用，但仍受程序号和版本校验。
        ProtocolConfig protocol = config(Map.of("auth", "require-sys"));
        OncRpcMessageMapper mapper = mapper(protocol, service(Arithmetic.class));
        assertNull(mapper.decodeRequest(call(0, new byte[0])));
    }

    @Test
    public void requireSystemAuthenticationAcceptsSystemBusinessCalls() {
        // 提供有效 AUTH_SYS 的业务请求可以正常解码。
        ProtocolConfig protocol = config(Map.of("auth", "require-sys"));
        OncRpcAuth identity = OncRpcAuth.newSystem(1, "peer", 2, 3, new long[0]);
        OncRpcMessage message = call(0x20000001L, 2, 2, identity, OncRpcAuth.newNone(), new byte[0]);
        RequestPayload request = mapper(protocol, service(Arithmetic.class)).decodeRequest(message);
        assertEquals("ping", request.getTargetMethod());
    }

    @Test
    public void outgoingSystemAuthenticationUsesConfiguredIdentity() {
        // 客户端配置沿用标准 XDR AUTH_SYS 字段。
        ProtocolConfig protocol = config(Map.of("auth", "sys", "authMachine", "caller", //
                "authUid", "4294967295", "authGid", "7", "authStamp", "13", "authGroups", "8, 9"));
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        RequestPayload request = request(service, "ping");
        OncRpcMessage call = mapper(protocol, service).encodeRequest(request, 1);

        // 独立读取身份内容，确认使用配置而非固定本机默认值。
        Map<String, Object> identity = call.getCredentials().getSystemIdentity();
        assertEquals("caller", identity.get("machineName"));
        assertEquals(0xffffffffL, identity.get("uid"));
        assertEquals(7L, identity.get("gid"));
        assertEquals(13L, identity.get("stamp"));
        assertEquals(List.of(8L, 9L), identity.get("groups"));
    }

    @Test
    public void unknownAuthenticationConfigurationIsRejected() {
        // 无法实现的认证机制不能在启动后退回匿名。
        ProtocolConfig protocol = config(Map.of("auth", "gss"));
        assertThrows(IllegalArgumentException.class, () -> mapper(protocol));
    }

    @Test
    public void negativeAuthenticationUidIsRejected() {
        // UID 是 XDR unsigned int。
        ProtocolConfig protocol = config(Map.of("auth", "sys", "authUid", "-1"));
        assertThrows(IllegalArgumentException.class, () -> mapper(protocol));
    }

    @Test
    public void overflowingAuthenticationGidIsRejected() {
        // 超出 32 位的 GID 不能截断到标准凭证中。
        ProtocolConfig protocol = config(Map.of("auth", "sys", "authGid", "4294967296"));
        assertThrows(IllegalArgumentException.class, () -> mapper(protocol));
    }

    @Test
    public void malformedAuthenticationGroupIsRejected() {
        // 分组列表不能包含缺少数字的项目。
        ProtocolConfig protocol = config(Map.of("auth", "sys", "authGroups", "8,"));
        assertThrows(IllegalArgumentException.class, () -> mapper(protocol));
    }

    @Test
    public void primitiveArrayUsesAnXdrVariableLengthSequence() {
        // Java 基础类型数组映射为带长度的 XDR 序列。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "array", new int[] { 4, 9 }), 1);
        RequestPayload decoded = mapper.decodeRequest(call);
        assertArrayEquals(new int[] { 4, 9 }, (int[]) decoded.getParameterValues().get(0));
    }

    @Test
    public void genericListPreservesElementOrder() {
        // List 的泛型元素使用对应的 XDR 映射。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "list", List.of(9, 4)), 1);
        RequestPayload decoded = mapper.decodeRequest(call);
        assertEquals(List.of(9, 4), decoded.getParameterValues().get(0));
    }

    @Test
    public void opaqueByteArrayUsesOpaqueEncodingRatherThanAnIntegerArray() {
        // byte[] 是 XDR opaque，不是逐字节写成四字节整数的数组。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "bytes", new byte[] { 1, 2, 3 }), 1);
        assertArrayEquals(new byte[] { 0, 0, 0, 3, 1, 2, 3, 0 }, call.getBody());
    }

    @Test
    public void enumUsesItsDefaultDeclarationOrdinal() {
        // 没有显式 IDL 映射时采用枚举声明顺序。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "tone", Tone.BLUE), 1);
        assertEquals(1, input(call.getBody()).readInt());
    }

    @Test
    public void unknownEnumOrdinalReportsGarbageArguments() {
        // 线上未知枚举值不会映射到任意默认枚举。
        XdrOutput body = output();
        body.writeInt(8);
        OncRpcMessageMapper mapper = mapper(config(), service(CollectionsContract.class));
        OncRpcMessage message = call(6, body.toByteArray());
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(message));
        assertEquals(OncRpcMessage.GARBAGE_ARGS, failure.getAcceptStatus());
    }

    @Test
    public void arrayEncodingHonorsTheConfiguredContainerLimit() {
        // 数组长度在写出前受容器上限约束。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        ProtocolConfig protocol = config(Map.of("maxContainerSize", "1"));
        OncRpcMessageMapper mapper = mapper(protocol, service);
        RequestPayload request = request(service, "array", new int[] { 4, 9 });
        assertThrows(IllegalArgumentException.class, () -> mapper.encodeRequest(request, 1));
    }

    @Test
    public void customStructCodecControlsTheDeclaredWireLayout() {
        // 未知 DTO 只通过本地显式编码器绑定，不根据 Java 字段反射猜测 IDL。
        ServiceDomain<StructContract> service = service(StructContract.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(StructContract.class);
        resource.setVersion(2);
        resource.codec(Point.class, pointCodec());
        service.setBindResource("oncrpc", resource);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "mirror", new Point(7, 11)), 1);

        // 输入和成功输出都复用该资源声明的结构编码器。
        RequestPayload decoded = mapper.decodeRequest(call);
        Point incoming = (Point) decoded.getParameterValues().get(0);
        assertEquals(7, incoming.x);
        assertEquals(11, incoming.y);
        OncRpcMessage reply = mapper.encodeResponse(decoded, success(new Point(13, 17)), call);
        Point result = (Point) mapper.decodeResponse(decoded, reply).getReturnData();
        assertEquals(13, result.x);
        assertEquals(17, result.y);
    }

    @Test
    public void customEnumCodecCanUseNonOrdinalIdlValues() {
        // IDL 枚举编号不连续时，显式编码器覆盖默认 ordinal 映射。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(CollectionsContract.class);
        resource.setVersion(2);
        resource.codec(Tone.class, new XdrCodec<>() {
            public Tone read(XdrInput input) {
                return input.readInt() == 23 ? Tone.BLUE : Tone.RED;
            }

            public void write(XdrOutput output, Tone value) {
                output.writeInt(value == Tone.BLUE ? 23 : 19);
            }
        });
        service.setBindResource("oncrpc", resource);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcMessage call = mapper.encodeRequest(request(service, "tone", Tone.BLUE), 1);
        assertEquals(23, input(call.getBody()).readInt());
    }

    @Test
    public void publicationRejectsANonXdrSerializer() {
        // 序列化冲突应在服务发布阶段暴露。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.setSerializeType("json");
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationValidationDoesNotRepeatProtocolSelection() {
        // 协议声明由内核筛选，提供者仅校验当前接口自身的契约。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.addBindProtocol("rsf");
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationAcceptsASubsetFacadeWithAnExplicitResource() {
        // 用户门面无需重复注解，完整线契约由绑定资源提供。
        ServiceDomain<PingFacade> service = service(PingFacade.class);
        Object bindResource = new OncRpcServiceResource(Arithmetic.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNotNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationPreparesAnEndpointBindingFromTheBorrowedResource() {
        // 借入的契约保留身份，端点各自准备类型和方法绑定。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(Arithmetic.class);
        resource.setVersion(2);
        ProtocolConfig protocol = config();
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        OncRpcServiceBinding binding = OncRpcMessageMapper.prepareServiceDefinition(protocol, meta);
        assertSame(resource, binding.getResource());
    }

    @Test
    public void publicationIgnoresAnotherProtocolsResource() {
        // 其他协议绑定的资源不参与 ONC 注解契约准备。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.setBindResource("soap", new Object());
        ProtocolConfig protocol = config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, meta));
    }

    @Test
    public void publicationDeclinesForeignResourceWithAnotherSerializer() {
        // 同时采用异协议资源与序列化器的服务，不应中断整体发布。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.setBindResource("soap", new Object());
        service.setSerializeType("json");
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void foreignResourceRejectionDoesNotReplaceAcceptedEndpointDefinition() {
        // 前一个端点已经接受合法服务，并把定义保存在所属协议分组中。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        ProtocolConfig protocol = config();
        OncRpcProtocol provider = new OncRpcProtocol();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding accepted = provider.prepareBinding(protocol, null, declaration);
        ProtocolMeta published = new ProtocolMeta(service, null, null, Map.of("first-endpoint", accepted));
        service.replaceProtocolPublication(Map.of("oncrpc", published));

        // 后一个端点拒绝异协议资源，不改写之前已发布的定义和端点快照。
        ProtocolMeta invalid = new ProtocolMeta(service, null, new Object(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> provider.prepareBinding(protocol, null, invalid));
        assertSame(published, service.getProtocolMeta("oncrpc"));
        assertSame(accepted, published.getEndpoint("first-endpoint"));
        assertTrue(accepted.getDefinition() instanceof OncRpcServiceBinding);
    }

    @Test
    public void publicationRejectsMessageServiceBindings() {
        // 整个绑定的消息模式与 ONC RPC 的请求响应语义不兼容。
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        service.setMessage(true);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsMessageAnnotatedMethods() {
        // 方法级的无响应标记同样不能与标准响应契约混用。
        ServiceDomain<MessageMethod> service = service(MessageMethod.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsMessageAnnotatedInterfaces() {
        // 类型级的无响应标记不能覆盖 ONC RPC 过程语义。
        ServiceDomain<MessageType> service = service(MessageType.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsUnknownDtoTypesWithoutACodec() {
        // 普通 DTO 不隐式采用 JSON 或 Java 序列化。
        ServiceDomain<UnknownDto> service = service(UnknownDto.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationAcceptsExplicitDtoCodecs() {
        // 启动校验认可资源中明确提供的结构映射。
        ServiceDomain<StructContract> service = service(StructContract.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(StructContract.class);
        resource.setVersion(2);
        resource.codec(Point.class, pointCodec());
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, resource, Map.of());
        assertNotNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsMismatchedFacadeParameters() {
        // 相同方法名不意味着同一个线契约。
        ServiceDomain<WrongParameters> service = service(WrongParameters.class);
        Object bindResource = new OncRpcServiceResource(Arithmetic.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsMismatchedFacadeReturnTypes() {
        // 返回类型也必须与资源声明一致。
        ServiceDomain<WrongResult> service = service(WrongResult.class);
        Object bindResource = new OncRpcServiceResource(Arithmetic.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsInputStreams() {
        // 持续字节输入不是 ONC RPC/XDR 的有限值参数。
        ServiceDomain<InputContract> service = service(InputContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsOutputStreams() {
        // 输出流不能通过普通 XDR 过程传递。
        ServiceDomain<OutputContract> service = service(OutputContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsReaders() {
        // 持续字符输入与 XDR 有限字符串不同。
        ServiceDomain<ReaderContract> service = service(ReaderContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsWriters() {
        // 字符写入流不能伪装为普通返回值。
        ServiceDomain<WriterContract> service = service(WriterContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsFlowPublishers() {
        // 本插件不把单次 XDR 消息扩展为非标准流式 RPC。
        ServiceDomain<PublisherContract> service = service(PublisherContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsUnsupportedParameterizedContainers() {
        // 任意 Map 泛型不具有唯一的标准 XDR 布局。
        ServiceDomain<MapContract> service = service(MapContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsUnresolvedGenericTypes() {
        // 未被具体化的类型变量不能在网络上推断。
        ServiceDomain<GenericContract> service = service(GenericContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void publicationRejectsInvalidAuthenticationConfiguration() {
        // 服务发布时验证认证配置，避免第一笔调用才暴露启动配置错误。
        ProtocolConfig protocol = config(Map.of("auth", "gss"));
        ServiceDomain<Arithmetic> service = service(Arithmetic.class);
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        assertThrows(IllegalArgumentException.class, //
                () -> OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void explicitCodecOverridesAParameterizedCollectionWireLayout() {
        // 某个 IDL 的固定长度序列不应强制采用默认的变长 List 布局。
        ServiceDomain<CollectionsContract> service = service(CollectionsContract.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(CollectionsContract.class);
        resource.setVersion(2);
        resource.codec(List.class, new XdrCodec<>() {
            @Override
            public List read(XdrInput input) {
                return List.of(input.readInt());
            }

            @Override
            public void write(XdrOutput output, List value) {
                output.writeInt((Integer) value.get(0));
            }
        });
        service.setBindResource("oncrpc", resource);
        OncRpcMessageMapper mapper = mapper(config(), service);

        // 自定义映射控制参数正文及返回值，省略默认的变长容器计数。
        RequestPayload request = request(service, "list", List.of(23));
        OncRpcMessage call = mapper.encodeRequest(request, 1);
        assertArrayEquals(new byte[] { 0, 0, 0, 23 }, call.getBody());
        assertEquals(List.of(23), mapper.decodeRequest(call).getParameterValues().get(0));
    }

    @Test
    public void publicationRejectsFacadeGenericArgumentsThatDifferFromTheResource() {
        // 即使 List 擦除后的参数类型相同，元素类型也必须符合本地 IDL 契约。
        ServiceDomain<WrongGenericFacade> service = service(WrongGenericFacade.class);
        Object bindResource = new OncRpcServiceResource(CollectionsContract.class);
        ProtocolConfig protocol = config();
        ProtocolMeta declaration = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration));
    }

    @Test
    public void ignoredFacadeMethodCannotBeInvokedThroughItsBoundResource() {
        // 绑定完整资源不会把用户通过 @MetIgnore 隐藏的方法重新发布。
        ServiceDomain<IgnoredFacade> service = service(IgnoredFacade.class);
        OncRpcServiceResource resource = new OncRpcServiceResource(Arithmetic.class);
        resource.setVersion(2);
        service.setBindResource("oncrpc", resource);
        OncRpcMessageMapper mapper = mapper(config(), service);
        OncRpcException failure = assertThrows(OncRpcException.class, () -> mapper.decodeRequest(call(2, new byte[0])));
        assertEquals(OncRpcMessage.PROC_UNAVAIL, failure.getAcceptStatus());
    }

    @Test
    public void heterogeneousScalarSignaturePreservesItsDeclaredTypes() {
        // 一个真实过程可同时接收各种基本 XDR 标量，顺序和类型必须保持不变。
        ServiceDomain<ScalarContract> service = service(ScalarContract.class);
        OncRpcMessageMapper mapper = mapper(config(), service);
        RequestPayload request = request(service, "scalars", 7L, true, 1.5f, 2.5d, "hello");
        OncRpcMessage call = mapper.encodeRequest(request, 1);

        // 参数仍按该方法的 Java 声明交给 Dispatcher。
        RequestPayload decoded = mapper.decodeRequest(call);
        assertEquals(List.of(7L, true, 1.5f, 2.5d, "hello"), decoded.getParameterValues());
        assertEquals(List.of("long", "boolean", "float", "double", "java.lang.String"), decoded.getParameterTypes());
    }

    private static ProtocolConfig config() {
        return config(Map.of());
    }

    private static ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("oncrpc", null, options);
    }

    private static <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getSimpleName());
        service.setServiceType(MetServiceType.Provider);

        // 编解码夹具显式配置程序版本，业务接口不再携带 ONC 版本注解。
        OncRpcServiceResource resource = OncRpcServiceResource.infer(type);
        if (resource != null) {
            long version = 2;
            if (type == VersionOne.class) {
                version = 1;
            } else if (type == VersionThree.class) {
                version = 3;
            }
            resource.setVersion(version);
            service.setBindResource("oncrpc", resource);
        }
        return service;
    }

    private static OncRpcMessageMapper mapper(ProtocolConfig protocol, ServiceDomain<?>... services) {
        // 真实服务元信息配合无资源上下文，单测不启动网络或业务容器。
        MetContext context = mock(MetContext.class);
        MetSettings settings = mock(MetSettings.class);
        when(settings.getDefaultTimeout()).thenReturn(3400);
        when(context.getSettings()).thenReturn(settings);
        List<String> identifiers = new ArrayList<>();
        for (ServiceDomain<?> service : services) {
            // 与真实发布流程一样，只有已接受的端点才能暴露提供者。
            Set<String> selected = service.getBindProtocols();
            if (selected.isEmpty() || selected.contains(protocol.name())) {
                ProtocolMeta declaration = service.getProtocolMeta(protocol.name());
                if (declaration == null) {
                    declaration = new ProtocolMeta(service, null, null, Map.of());
                }
                OncRpcServiceBinding prepared = OncRpcMessageMapper.prepareServiceDefinition(protocol, declaration);
                if (prepared != null) {
                    ProtocolBinding binding = new ProtocolBinding(null, prepared, null);
                    ProtocolMeta metadata = new ProtocolMeta(service, null, declaration.getBindResource(), //
                            Map.of("oncrpc-test", binding));
                    service.replaceProtocolPublication(Map.of(protocol.name(), metadata));
                }
            }
            String identifier = service.getBindID();
            identifiers.add(identifier);
            doReturn(service).when(context).getServiceInfo(identifier);
            doReturn(service).when(context).getServiceInfo( //
                    service.getBindGroup(), service.getBindName(), service.getBindVersion());
        }
        when(context.getServiceIDs()).thenReturn(identifiers);
        return new OncRpcMessageMapper(protocol, new RouteMountIndex("oncrpc-test", protocol), context);
    }

    private static RequestPayload request(ServiceDomain<?> service, String method, Object... arguments) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method);
        for (Object argument : arguments) {
            request.addParameter(argument.getClass().getName(), argument);
        }
        return request;
    }

    private static ResponsePayload success(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private static OncRpcMessage call(long procedure, byte[] body) {
        return call(0x20000001L, 2, procedure, OncRpcAuth.newNone(), OncRpcAuth.newNone(), body);
    }

    private static OncRpcMessage call(long program, long version, long procedure, //
            OncRpcAuth credentials, OncRpcAuth verifier, byte[] body) {
        return OncRpcMessage.newCall(51, 2, program, version, procedure, credentials, verifier, body);
    }

    private static XdrInput input(byte[] body) {
        return new XdrInput(body, 4096, 100, 16);
    }

    private static XdrOutput output() {
        return new XdrOutput(4096, 100, 16);
    }

    private static XdrCodec<Point> pointCodec() {
        return new XdrCodec<>() {
            @Override
            public Point read(XdrInput input) {
                int x = input.readInt();
                int y = input.readInt();
                return new Point(x, y);
            }

            @Override
            public void write(XdrOutput output, Point value) {
                output.writeInt(value.x);
                output.writeInt(value.y);
            }
        };
    }

    @MetService(id = 0x20000001L)
    public interface Arithmetic {
        @MetMethod(id = 1)
        int subtract(int left, int right);

        @MetMethod(id = 2)
        void ping();
    }

    @MetService(id = 0x20000001L)
    public interface VersionOne extends Arithmetic {
    }

    @MetService(id = 0x20000001L)
    public interface VersionThree extends Arithmetic {
    }

    @MetService(id = 0x20000001L)
    public interface CollectionsContract {
        @MetMethod(id = 3)
        int[] array(int[] values);

        @MetMethod(id = 4)
        List<Integer> list(List<Integer> values);

        @MetMethod(id = 5)
        byte[] bytes(byte[] values);

        @MetMethod(id = 6)
        Tone tone(Tone value);
    }

    @MetService(id = 0x20000001L)
    public interface StructContract {
        @MetMethod(id = 7)
        Point mirror(Point value);
    }

    public enum Tone {
        @MetEnumValue(id = 0) RED,
        @MetEnumValue(id = 1) BLUE
    }

    public static class Point {
        private final int x;
        private final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public interface PingFacade {
        void ping();
    }

    public interface WrongGenericFacade {
        List<Long> list(List<Long> values);
    }

    public interface IgnoredFacade {
        @MetIgnore
        void ping();
    }

    @MetService(id = 0x20000001L)
    public interface ScalarContract {
        @MetMethod(id = 1)
        String scalars(long count, boolean enabled, float ratio, double total, String value);
    }

    public interface WrongParameters {
        int subtract(long left, long right);
    }

    public interface WrongResult {
        long subtract(int left, int right);
    }

    @MetService(id = 0x20000001L)
    public interface UnknownDto {
        @MetMethod(id = 1)
        Object unknown(Object value);
    }

    @MetService(id = 0x20000001L)
    public interface MessageMethod {
        @MetMessage
        @MetMethod(id = 1)
        void notifyPeer();
    }

    @MetMessage
    @MetService(id = 0x20000001L)
    public interface MessageType {
        @MetMethod(id = 1)
        void notifyPeer();
    }

    @MetService(id = 0x20000001L)
    public interface InputContract {
        @MetMethod(id = 1)
        InputStream read();
    }

    @MetService(id = 0x20000001L)
    public interface OutputContract {
        @MetMethod(id = 1)
        void write(OutputStream value);
    }

    @MetService(id = 0x20000001L)
    public interface ReaderContract {
        @MetMethod(id = 1)
        Reader read();
    }

    @MetService(id = 0x20000001L)
    public interface WriterContract {
        @MetMethod(id = 1)
        void write(Writer value);
    }

    @MetService(id = 0x20000001L)
    public interface PublisherContract {
        @MetMethod(id = 1)
        Flow.Publisher<String> events();
    }

    @MetService(id = 0x20000001L)
    public interface MapContract {
        @MetMethod(id = 1)
        Map<String, Integer> lookup();
    }

    @MetService(id = 0x20000001L)
    public interface GenericContract<T> {
        @MetMethod(id = 1)
        T lookup();
    }
}
