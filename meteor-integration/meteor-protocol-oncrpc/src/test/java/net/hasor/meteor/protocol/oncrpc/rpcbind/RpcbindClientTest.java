/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.domain.ProtocolConfig;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RpcbindClientTest {
    private MetContext        context;
    private PortMapperService ports;
    private RpcbindV3Service  version3;
    private RpcbindV4Service  version4;

    @Before
    @SuppressWarnings("unchecked")
    public void prepareBorrowedContext() {
        this.context = mock(MetContext.class);
        this.ports = mock(PortMapperService.class);
        this.version3 = mock(RpcbindV3Service.class);
        this.version4 = mock(RpcbindV4Service.class);
        MetClient client = mock(MetClient.class);
        when(this.context.getRsfClient(any(InterAddress.class))).thenReturn(client);

        // 预先订阅的正式契约可以被复用，不为每个 RpcbindClient 重复注册。
        MetBindInfo<PortMapperService> portsInfo = mock(MetBindInfo.class);
        MetBindInfo<RpcbindV3Service> v3Info = mock(MetBindInfo.class);
        MetBindInfo<RpcbindV4Service> v4Info = mock(MetBindInfo.class);
        when(this.context.getServiceInfo(PortMapperService.class)).thenReturn(portsInfo);
        when(this.context.getServiceInfo(RpcbindV3Service.class)).thenReturn(v3Info);
        when(this.context.getServiceInfo(RpcbindV4Service.class)).thenReturn(v4Info);
        when(client.getRemote(portsInfo)).thenReturn(this.ports);
        when(client.getRemote(v3Info)).thenReturn(this.version3);
        when(client.getRemote(v4Info)).thenReturn(this.version4);
        this.configure("tcp", "oncrpc");
    }

    @Test
    public void portLookupUsesRequestedUdpProtocolEvenOverTcp() throws Exception {
        // v2 GETPORT 的 pm_prot 明确允许在 TCP 调用中查询 UDP。
        when(this.ports.getPort(any())).thenReturn(8124L);
        int port = this.client("oncrpc", "127.0.0.1").getPort(1, 2, "udp");
        assertEquals(8124, port);
        ArgumentCaptor<PortMapperMapping> request = ArgumentCaptor.forClass(PortMapperMapping.class);
        verify(this.ports).getPort(request.capture());
        assertEquals(17, request.getValue().getProtocol());
    }

    @Test
    public void unknownPortRemainsZero() throws Exception {
        // 未注册项保持标准 v2 的零端口语义。
        assertEquals(0, this.client("oncrpc", "127.0.0.1").getPort(1, 2, "tcp"));
    }

    @Test
    public void version2RejectsIpv6Netid() {
        // v2 只有 IP 协议号，不能冒充具有 IPv6 地址语义的 rpcbind。
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> client.getPort(1, 2, "tcp6"));
    }

    @Test
    public void invalidRemotePortIsRejected() throws Exception {
        // 标准结果虽是 uint32，IP 端口仍不能大于 65535。
        when(this.ports.getPort(any())).thenReturn(65536L);
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> client.getPort(1, 2, "tcp"));
    }

    @Test
    public void version3EmptyAddressMeansAbsent() throws Exception {
        // rpcbind 用空字符串表示未找到，外层 API 以 null 表达。
        when(this.version3.getAddress(any())).thenReturn("");
        assertNull(this.client("oncrpc", "127.0.0.1").getAddress(1, 2, "tcp"));
    }

    @Test
    public void version4ExactLookupUsesProcedureNineContract() throws Exception {
        // 精确版本查询使用 v4 的独立过程，不退回 GETADDR。
        when(this.version4.getVersionAddress(any())).thenReturn("127.0.0.1.31.187");
        String address = this.client("oncrpc", "127.0.0.1").getVersionAddress(1, 2, "tcp");
        assertEquals("127.0.0.1.31.187", address);
        verify(this.version4, never()).getAddress(any());
    }

    @Test
    public void version4GetAddressUsesItsOwnProgramVersion() throws Exception {
        // GETADDR 与 GETVERSADDR 是不同控制过程。
        when(this.version4.getAddress(any())).thenReturn("127.0.0.1.31.187");
        String address = this.client("oncrpc", "127.0.0.1").getAddressV4(1, 2, "tcp");
        assertEquals("127.0.0.1.31.187", address);
        verify(this.version3, never()).getAddress(any());
    }

    @Test
    public void addressLookupRejectsNetidDifferentFromActualTransport() {
        // GETADDR 的 daemon 按调用链路选 netid，客户端不能声称 TCP 查询得到的是 UDP。
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> client.getAddress(1, 2, "udp"));
    }

    @Test
    public void schemeAliasResolvesConfiguredTransport() throws Exception {
        // 用户 scheme 别名由端点挂载解析，不通过名字猜测 TCP 或 UDP。
        this.configure("udp", "discovery");
        when(this.version3.getAddress(any())).thenReturn("127.0.0.1.31.188");
        String address = this.client("discovery", "127.0.0.1").getAddress(1, 2, "udp");
        assertEquals("127.0.0.1.31.188", address);
    }

    @Test
    public void ipv6TargetRequiresIpv6Netid() throws Exception {
        // IPv6 通道只接受 tcp6，而不是 tcp。
        when(this.version3.getAddress(any())).thenReturn("::1.31.187");
        String address = this.client("oncrpc", "::1").getAddress(1, 2, "tcp6");
        assertEquals("::1.31.187", address);
    }

    @Test
    public void unmountedTargetSchemeIsRejected() {
        // 构造控制客户端前必须在上下文配置真实的传输协议。
        assertThrows(IllegalArgumentException.class, () -> this.client("missing", "127.0.0.1"));
    }

    @Test
    public void remoteSetIsRejectedBeforeSendingControlRpc() {
        // SET/UNSET 只面向本机 rpcbind，发现查询仍允许远端。
        RpcbindClient client = this.client("oncrpc", "192.0.2.1");
        PortMapperMapping mapping = new PortMapperMapping(1, 2, 6, 8123);
        assertThrows(IllegalStateException.class, () -> client.setPort(mapping));
        verifyNoInteractions(this.ports);
    }

    @Test
    public void explicitSetReturnsDaemonDecisionWithoutOwnershipCache() throws Exception {
        // SET 每次都是显式 RPC，权限和所有权判定留给 rpcbind daemon。
        PortMapperMapping mapping = new PortMapperMapping(1, 2, 6, 8123);
        when(this.ports.set(mapping)).thenReturn(true, false);
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        assertTrue(client.setPort(mapping));
        assertFalse(client.setPort(mapping));
        verify(this.ports, times(2)).set(mapping);
    }

    @Test
    public void version2UnsetSendsProgramAndVersionWithoutTransport() throws Exception {
        // v2 UNSET 的协议与端口字段为零，作用于该程序版本的全部传输。
        when(this.ports.unset(any())).thenReturn(true);
        assertTrue(this.client("oncrpc", "127.0.0.1").unsetPort(10, 20));
        ArgumentCaptor<PortMapperMapping> request = ArgumentCaptor.forClass(PortMapperMapping.class);
        verify(this.ports).unset(request.capture());
        assertEquals(10, request.getValue().getProgram());
        assertEquals(20, request.getValue().getVersion());
        assertEquals(0, request.getValue().getProtocol());
    }

    @Test
    public void version3UnsetRetainsExplicitTransport() throws Exception {
        // v3 UNSET 保留 netid，避免用空字符串扩大删除范围。
        when(this.version3.unset(any())).thenReturn(true);
        assertTrue(this.client("oncrpc", "127.0.0.1").unsetMapping(10, 20, "udp"));
        ArgumentCaptor<RpcbindMapping> request = ArgumentCaptor.forClass(RpcbindMapping.class);
        verify(this.version3).unset(request.capture());
        assertEquals("udp", request.getValue().getNetid());
    }

    @Test
    public void version3SetRejectsNonLocalUniversalAddress() {
        // 通用地址不能把本机的 rpcbind 改为声明第三方主机服务。
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        RpcbindMapping mapping = new RpcbindMapping(1, 1, "tcp", "192.0.2.1.31.187", "owner");
        assertThrows(IllegalArgumentException.class, () -> client.setMapping(mapping));
    }

    @Test
    public void version3SetRejectsAddressFamilyMismatch() {
        // tcp6 的映射必须携带 IPv6 通用地址。
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        RpcbindMapping mapping = new RpcbindMapping(1, 1, "tcp6", "127.0.0.1.31.187", "owner");
        assertThrows(IllegalArgumentException.class, () -> client.setMapping(mapping));
    }

    @Test
    public void version3SetPreservesDaemonDecision() throws Exception {
        // 标准 false 结果不是本地异常，调用方可以按 daemon 的结果处理。
        RpcbindMapping mapping = new RpcbindMapping(1, 1, "tcp", "127.0.0.1.31.187", "owner");
        assertFalse(this.client("oncrpc", "127.0.0.1").setMapping(mapping));
        verify(this.version3).set(mapping);
    }

    @Test
    public void invalidVersion2TransportIsRejected() {
        // 控制 API 不允许把任意协议号当作 TCP/UDP 注册。
        RpcbindClient client = this.client("oncrpc", "127.0.0.1");
        PortMapperMapping mapping = new PortMapperMapping(1, 1, 132, 8123);
        assertThrows(IllegalArgumentException.class, () -> client.setPort(mapping));
    }

    @Test
    public void ipv4UniversalAddressUsesLastTwoOctetsAsPort() {
        // 地址正文不能整体按点分八位组当作端口。
        InterAddress address = RpcbindClient.toAddress("oncrpc", "127.0.0.1.31.187", "unit");
        assertEquals("127.0.0.1", address.getHost());
        assertEquals(8123, address.getPort());
    }

    @Test
    public void ipv6UniversalAddressPreservesLiteralHost() {
        // IPv6 的冒号保持在 host 中，只有最后两个点号分隔端口字节。
        InterAddress address = RpcbindClient.toAddress("oncrpc", "::1.31.187", "unit");
        assertEquals(8123, address.getPort());
        assertTrue(address.getHost().contains("::1"));
    }

    @Test
    public void emptyUniversalAddressMeansAbsent() {
        // rpcbind 未找到映射时不构造伪目标地址。
        assertNull(RpcbindClient.toAddress("oncrpc", "", "unit"));
    }

    @Test
    public void zeroUniversalPortMeansAbsent() {
        // 零端口不能作为业务调用目标。
        assertNull(RpcbindClient.toAddress("oncrpc", "127.0.0.1.0.0", "unit"));
    }

    @Test
    public void invalidUniversalPortOctetIsRejected() {
        // 单个端口字节必须落在 0 到 255 之间。
        assertThrows(IllegalArgumentException.class, //
                () -> RpcbindClient.toAddress("oncrpc", "127.0.0.1.256.1", "unit"));
    }

    @Test
    public void nonDecimalUniversalPortOctetIsRejected() {
        // 正负号与其它字符都不是通用地址的十进制端口字节。
        assertThrows(IllegalArgumentException.class, //
                () -> RpcbindClient.toAddress("oncrpc", "127.0.0.1.+1.1", "unit"));
    }

    @Test
    public void incompleteUniversalAddressIsRejected() {
        // 缺少两个端口字节时不尝试猜测地址。
        assertThrows(IllegalArgumentException.class, //
                () -> RpcbindClient.toAddress("oncrpc", "localhost.1", "unit"));
    }

    @Test
    public void controlCallsBorrowContextWithoutClosingIt() throws Exception {
        // 没有本客户端所有的全局资源，查询结束后上下文仍由创建者管理。
        this.client("oncrpc", "127.0.0.1").dumpMappings();
        verify(this.context, never()).close();
        verify(this.context, never()).publisher();
    }

    private RpcbindClient client(String scheme, String host) {
        return new RpcbindClient(this.context, new InterAddress(scheme, host, 111, "unit"));
    }

    private void configure(String transport, String scheme) {
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", scheme, Map.of());
        InterAddress address = InterAddress.forBinding(transport, "127.0.0.1", 0, "unit");
        ConnectorConfig connector = new ConnectorConfig("rpcbind", address, Map.of(), List.of(protocol), false);
        MetSettings settings = mock(MetSettings.class);
        when(settings.getConnectorConfigs()).thenReturn(List.of(connector));
        when(this.context.getSettings()).thenReturn(settings);
    }
}
