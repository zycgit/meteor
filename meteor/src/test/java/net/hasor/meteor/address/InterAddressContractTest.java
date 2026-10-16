/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterAddressContractTest {
    @Test
    public void canonicalUriAndSocketRoundTrip() throws Exception {
        // 解析地址时归一化协议名，保留主机、端口和单元。
        InterAddress address = new InterAddress("RSF://127.0.0.1:8000/zone");
        assertEquals("rsf", address.getSchema());
        assertEquals("zone", address.getFormUnit());
        assertEquals("127.0.0.1", address.getHost());
        assertEquals(8000, address.getPort());
        assertEquals("127.0.0.1:8000", address.getHostPort());

        // URI、Socket、字符串和组件构造形式均指向同一目标。
        assertEquals(address, new InterAddress(address.toURI()));
        assertEquals(8000, address.toSocketAddress().getPort());
        assertEquals("rsf://127.0.0.1:8000/zone", address.toString());
        assertEquals(address, new InterAddress("127.0.0.1", 8000, "zone"));
    }

    @Test
    public void hostEqualityIgnoresProtocolAndUnit() throws Exception {
        // 同一主机和端口可属于不同协议、单元。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        InterAddress otherProtocolAndUnit = new InterAddress("http", "127.0.0.1", 8000, "other");

        // 主机比较只比较连接目标。
        assertTrue(address.equalsHost("127.0.0.1:8000"));
        assertTrue(address.equalsHost(otherProtocolAndUnit));
    }

    @Test
    public void nullHostStringDoesNotMatch() throws Exception {
        // 字符串形式的空目标不匹配有效主机。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        assertFalse(address.equalsHost((String) null));
    }

    @Test
    public void nullHostAddressDoesNotMatch() throws Exception {
        // 地址形式的空目标不匹配有效主机。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        assertFalse(address.equalsHost((InterAddress) null));
    }

    @Test
    public void addressEqualityRejectsNull() throws Exception {
        // 完整地址不能等于空对象。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        assertNotEquals(address, null);
    }

    @Test
    public void addressEqualityRejectsItsStringRepresentation() throws Exception {
        // 相同文本不能替代地址对象参与完整地址相等比较。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        assertNotEquals(address, address.toString());
    }

    @Test
    public void addressEqualityDistinguishesProtocols() throws Exception {
        // 同一主机和单元采用不同协议时仍是不同服务地址。
        InterAddress address = new InterAddress("rsf://127.0.0.1:8000/zone");
        InterAddress http = new InterAddress("http", "127.0.0.1", 8000, "zone");
        assertNotEquals(address, http);
    }

    @Test
    public void caseInsensitiveEqualityWorksAsMapKey() throws Exception {
        // 仅主机和单元大小写不同的地址作为同一个映射键。
        InterAddress upper = new InterAddress("rsf://LOCALHOST:8000/ZONE");
        InterAddress lower = new InterAddress("rsf://localhost:8000/zone");
        assertEquals(upper, lower);
        Map<InterAddress, String> map = new HashMap<>();
        map.put(upper, "provider");

        // 相等地址共享散列值、查询位置和更新位置。
        assertEquals("provider", map.get(lower));
        assertEquals(upper.hashCode(), lower.hashCode());
        map.put(lower, "replacement");
        assertEquals(1, map.size());
        assertEquals("replacement", map.get(upper));
    }

    @Test
    public void differentProtocolsRemainDistinctMapKeys() throws Exception {
        // 同一目标主机的两个协议在映射中各占一个位置。
        Map<InterAddress, String> map = new HashMap<>();
        map.put(new InterAddress("rsf://localhost:8000/zone"), "rsf");
        map.put(new InterAddress("http", "localhost", 8000, "zone"), "http");
        assertEquals(2, map.size());
    }

    @Test
    public void localHostAliasResolvesWithoutChangingItsDeclaredAddress() throws Exception {
        // local 别名用于解析本机地址，同时保留原始声明文本。
        InterAddress address = new InterAddress("rsf://local:8000/unit");
        String resolvedHost = address.getHost();
        assertEquals("rsf://local:8000/unit", address.toString());
        assertNotNull(resolvedHost);
        assertFalse(resolvedHost.isEmpty());
        assertNotEquals("local", resolvedHost);
    }

    @Test
    public void ipv6UriRoundTrip() throws Exception {
        // IPv6 地址往返 URI 并转换为 Socket 后保留原目标和端口。
        InterAddress address = new InterAddress("rsf://[::1]:8000/zone");
        assertEquals(address, new InterAddress(address.toURI()));
        assertEquals(8000, address.toSocketAddress().getPort());
    }

    @Test
    public void ipv6ComponentConstructorProducesParseableAddress() throws Exception {
        // 组件构造的 IPv6 地址在文本中补足括号，能够再次解析。
        InterAddress address = new InterAddress("rsf", "::1", 8000, "zone");
        InterAddress fromUri = new InterAddress(address.toURI());
        InterAddress fromText = new InterAddress(address.toHostSchema());
        assertEquals(fromUri, fromText);
    }

    @Test
    public void absentUriIsRejected() {
        // 地址校验拒绝空 URI。
        assertFalse(InterAddress.checkFormat(null));
    }

    @Test
    public void missingHostIsRejected() {
        // 缺少主机的 URI 不是可调用服务地址。
        URI address = URI.create("rsf:/zone");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void zeroServicePortIsRejected() {
        // 远程服务地址必须使用实际端口，不能使用绑定占位端口零。
        URI address = URI.create("rsf://127.0.0.1:0/zone");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void localEndpointCanRequestAnEphemeralPortWithoutRelaxingServiceAddresses() {
        // 本地绑定允许端口零，但它仍不能作为远程服务地址使用。
        InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        assertEquals(0, local.getPort());
        assertEquals("tcp://127.0.0.1:0/default", local.toHostSchema());
        assertFalse(InterAddress.checkFormat(URI.create(local.toHostSchema())));
    }

    @Test
    public void missingPortIsRejected() {
        // 服务地址缺少端口时返回校验失败。
        URI address = URI.create("rsf://127.0.0.1/zone");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void outOfRangePortIsRejected() {
        // 服务地址不能使用超过端口上限的数值。
        URI address = URI.create("rsf://127.0.0.1:65536/zone");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void missingSchemeIsRejected() {
        // 服务地址必须声明协议。
        URI address = URI.create("//127.0.0.1:8000/zone");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void malformedUnitReturnsFalseInsteadOfThrowing() {
        // 只有路径分隔符的地址缺少单元，校验返回 false。
        URI address = URI.create("rsf://127.0.0.1:8000/");
        assertFalse(InterAddress.checkFormat(address));
    }

    @Test
    public void invalidUnitSuffixIsNotAcceptedByPartialMatch() {
        // 单元尾部含非法字符时，不能仅匹配合法前缀就放行。
        URI address = URI.create("rsf://127.0.0.1:8000/zone!");
        assertFalse(InterAddress.checkFormat(address));
    }
}
