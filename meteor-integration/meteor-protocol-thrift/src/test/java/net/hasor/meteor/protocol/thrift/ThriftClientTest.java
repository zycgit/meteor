/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.mapping.NoteFailure;
import net.hasor.example.protocol.thrift.mapping.NoteService;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ServiceDomain;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 客户端只适配代理及异常，RPC 调度仍使用既有 MetClient。 */
public class ThriftClientTest {
    @Test
    public void declaredOrdinaryExceptionRetainsItsIdentity() throws Exception {
        // 普通异常通过已有同步调用的 ExecutionException 交付。
        NoteFailure expected = new NoteFailure();
        expected.setCode(409);
        MetClient core = this.failingClient(new ExecutionException(expected));
        ServiceDomain<NoteService> service = new ServiceDomain<>(NoteService.class);
        NoteService remote = new ThriftClient(core).getRemote(service);

        // 只保留注解中声明的业务异常，不要求它继承官方 TException。
        NoteFailure failure = assertThrows(NoteFailure.class, () -> remote.fail(409));
        assertSame(expected, failure);
    }

    @Test
    public void constructorRequiresTheExistingMeteorClient() {
        // 没有可委托的客户端时不能创建看似可用的远程入口。
        assertThrows(NullPointerException.class, () -> new ThriftClient(null));
    }

    @Test
    public void getRemoteReturnsAProxyOfThePublishedContract() {
        // 使用发布绑定的接口创建代理，不另外生成业务接口。
        MetClient core = mock(MetClient.class);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 获取代理本身不会提前调用底层 RPC。
        assertTrue(Proxy.isProxyClass(remote.getClass()));
        assertTrue(remote instanceof ThriftDemoService);
        verifyNoInteractions(core);
    }

    @Test
    public void invocationPassesTheBoundServiceSignatureAndArguments() throws Exception {
        // 远程结果由已有同步调用入口提供。
        MetClient core = mock(MetClient.class);
        ServiceDomain<ThriftDemoService> service = this.service();
        when(core.syncInvoke(same(service), eq("add"), any(), any())).thenReturn(11);
        ThriftDemoService remote = new ThriftClient(core).getRemote(service);

        // 代理保留 primitive Java 签名和参数顺序。
        assertEquals(11, remote.add(7, 4));
        verify(core).syncInvoke(service, "add", new Class<?>[] { int.class, int.class }, new Object[] { 7, 4 });
    }

    @Test
    public void zeroArgumentMethodUsesEmptyArgumentArrays() throws Exception {
        // 无参 void 方法仍通过同一正式客户端入口调用。
        MetClient core = mock(MetClient.class);
        ServiceDomain<ThriftDemoService> service = this.service();
        ThriftDemoService remote = new ThriftClient(core).getRemote(service);

        // JDK 代理提供的 null 参数数组不能传给调用层。
        remote.ping();
        verify(core).syncInvoke(service, "ping", new Class<?>[0], new Object[0]);
    }

    @Test
    public void onewayMethodStillDelegatesToTheCoreLifecycle() throws Exception {
        // 消息方法的提交确认继续由 MetClient 和协议会话管理。
        MetClient core = mock(MetClient.class);
        ServiceDomain<ThriftDemoService> service = this.service();
        ThriftDemoService remote = new ThriftClient(core).getRemote(service);

        // 适配器不在本地丢弃通知调用。
        remote.notify("event");
        verify(core).syncInvoke(service, "notify", new Class<?>[] { String.class }, new Object[] { "event" });
    }

    @Test
    public void equalsUsesProxyIdentityWithoutInvokingRpc() {
        // 同一个绑定创建的两个代理仍是独立对象。
        MetClient core = mock(MetClient.class);
        ThriftClient client = new ThriftClient(core);
        ServiceDomain<ThriftDemoService> service = this.service();
        ThriftDemoService first = client.getRemote(service);
        ThriftDemoService second = client.getRemote(service);

        // equals 与服务是否相同无关，也不会发送远程方法调用。
        assertEquals(first, first);
        assertNotEquals(first, second);
        assertNotEquals(first, null);
        assertNotEquals(first, new Object());
        verifyNoInteractions(core);
    }

    @Test
    public void hashCodeUsesProxyIdentityWithoutInvokingRpc() {
        // hashCode 属于本地代理对象，不需要服务端提供对应方法。
        MetClient core = mock(MetClient.class);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());
        assertEquals(System.identityHashCode(remote), remote.hashCode());
        verifyNoInteractions(core);
    }

    @Test
    public void toStringIncludesTheServiceBindingWithoutInvokingRpc() {
        // 日志显示绑定身份，同时保持 Object 方法在本地执行。
        MetClient core = mock(MetClient.class);
        ServiceDomain<ThriftDemoService> service = this.service();
        ThriftDemoService remote = new ThriftClient(core).getRemote(service);
        assertEquals("ThriftClient[" + service.getBindID() + "]", remote.toString());
        verifyNoInteractions(core);
    }

    @Test
    public void declaredThriftFailureIsUnwrappedWithoutChangingItsIdentity() throws Exception {
        // 底层 Future 将 IDL 声明异常包在 ExecutionException 中。
        DeclaredFailure declared = new DeclaredFailure(17, "denied");
        MetClient core = this.failingClient(new ExecutionException(declared));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 业务调用方直接捕获生成的异常，字段和原始实例都保留。
        DeclaredFailure failure = assertThrows(DeclaredFailure.class, () -> remote.fail("denied"));
        assertSame(declared, failure);
        assertEquals(17, failure.getCode());
        assertEquals("denied", failure.getReason());
    }

    @Test
    public void thriftApplicationFailureKeepsItsOriginalErrorCode() throws Exception {
        // 对端协议异常已经带有调用方需要识别的 Thrift 错误码。
        TApplicationException expected = new TApplicationException(TApplicationException.UNKNOWN_METHOD, "absent");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 不把已有的应用异常重新包装成 INTERNAL_ERROR。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.echo("value"));
        assertSame(expected, failure);
        assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
    }

    @Test
    public void thriftTransportFailureKeepsItsOriginalErrorCode() throws Exception {
        // 已分类的传输异常不能在代理边界丢失错误类别。
        TTransportException expected = new TTransportException(TTransportException.NOT_OPEN, "closed");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 调用方取得原始传输异常实例。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertSame(expected, failure);
        assertEquals(TTransportException.NOT_OPEN, failure.getType());
    }

    @Test
    public void genericThriftFailureRemainsAThriftException() throws Exception {
        // TException 基类也是生成接口允许调用方处理的异常。
        TException expected = new TException("protocol failed");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());
        assertSame(expected, assertThrows(TException.class, () -> remote.echo("value")));
    }

    @Test
    public void directTimeoutBecomesTimedOutTransportFailure() throws Exception {
        // 同步入口直接报告有界等待超时。
        TimeoutException expected = new TimeoutException("deadline");
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // Thrift 调用方得到传输超时类别和原始原因。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertEquals(TTransportException.TIMED_OUT, failure.getType());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void wrappedTimeoutBecomesTimedOutTransportFailure() throws Exception {
        // Future 也可能把 JDK 超时作为执行失败交付。
        TimeoutException expected = new TimeoutException("deadline");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 包装层不改变对调用方可见的超时类别。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertEquals(TTransportException.TIMED_OUT, failure.getType());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void meteorTimeoutBecomesTimedOutTransportFailure() throws Exception {
        // 内核的 RPC deadline 通过自己的异常报告。
        MetTimeoutException expected = new MetTimeoutException("rpc deadline");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 插件把内核期限映射为官方传输异常，并保留原因。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertEquals(TTransportException.TIMED_OUT, failure.getType());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void directMeteorTimeoutBecomesTimedOutTransportFailure() throws Exception {
        // 内核也可以在等待 Future 之前直接拒绝已经过期的调用。
        MetTimeoutException expected = new MetTimeoutException("rpc deadline");
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 直接报告的内核超时同样保持官方超时类别。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertEquals(TTransportException.TIMED_OUT, failure.getType());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void wrappedIoFailureBecomesTransportFailure() throws Exception {
        // 网络读写失败由同步入口以执行异常交付。
        IOException expected = new IOException("connection reset");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 原始 IO 原因属于传输错误，不能伪装为服务端应用异常。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertSame(expected, failure.getCause());
    }

    @Test
    public void directCancellationBecomesTransportFailure() throws Exception {
        // 调用在同步入口被取消，尚未取得业务响应。
        CancellationException expected = new CancellationException("cancelled");
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 取消通过 Thrift 允许的传输异常交付，保留原始取消原因。
        TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
        assertSame(expected, failure.getCause());
    }

    @Test
    public void interruptionRestoresTheFlagAndBecomesTransportFailure() throws Exception {
        // 同步等待被中断时，代理必须保留当前线程的中断信号。
        InterruptedException expected = new InterruptedException("stop");
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());
        assertFalse(Thread.currentThread().isInterrupted());

        try {
            // 原始中断作为传输失败原因返回，中断状态仍可被上层观察。
            TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
            assertSame(expected, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // 测试持有的线程状态不能污染后续测试。
            Thread.interrupted();
        }
    }

    @Test
    public void wrappedInterruptionRestoresTheFlagAndBecomesTransportFailure() throws Exception {
        // Future 的执行失败也可能携带底层中断原因。
        InterruptedException expected = new InterruptedException("worker stopped");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());
        assertFalse(Thread.currentThread().isInterrupted());

        try {
            // 解开执行异常后恢复线程中断，并保留原始原因。
            TTransportException failure = assertThrows(TTransportException.class, () -> remote.echo("value"));
            assertSame(expected, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // 线程状态归当前测试恢复，避免影响其他场景。
            Thread.interrupted();
        }
    }

    @Test
    public void unexpectedExecutionFailureBecomesApplicationFailure() throws Exception {
        // 非 Thrift 业务失败仍需符合生成接口的异常契约。
        IllegalStateException expected = new IllegalStateException("unexpected");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // INTERNAL_ERROR 保留错误信息及原始原因。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.echo("value"));
        assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        assertEquals("unexpected", failure.getMessage());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void directUnexpectedRuntimeFailureBecomesApplicationFailure() throws Exception {
        // 同步入口直接报告的运行期失败也需要符合 Thrift 异常约定。
        IllegalStateException expected = new IllegalStateException("unexpected");
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 转换为可捕获的应用异常，而不是从生成接口泄漏运行期失败。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.echo("value"));
        assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void executionFailureWithoutCauseRemainsDiagnosable() throws Exception {
        // 不完整的执行异常自身仍有错误信息。
        ExecutionException expected = new ExecutionException("missing cause", null);
        MetClient core = this.failingClient(expected);
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());

        // 未提供 cause 时保留 ExecutionException 作为原始原因。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.echo("value"));
        assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        assertEquals("missing cause", failure.getMessage());
        assertSame(expected, failure.getCause());
    }

    @Test
    public void virtualMachineErrorsAreNotDisguisedAsApplicationFailures() throws Exception {
        // Error 不属于服务的可恢复业务异常。
        AssertionError expected = new AssertionError("fatal");
        MetClient core = this.failingClient(new ExecutionException(expected));
        ThriftDemoService remote = new ThriftClient(core).getRemote(this.service());
        assertSame(expected, assertThrows(AssertionError.class, () -> remote.echo("value")));
    }

    private ServiceDomain<ThriftDemoService> service() {
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        service.setBindName("demo");
        return service;
    }

    private MetClient failingClient(Throwable failure) throws Exception {
        MetClient core = mock(MetClient.class);
        doThrow(failure).when(core).syncInvoke(any(), anyString(), any(), any());
        return core;
    }
}
