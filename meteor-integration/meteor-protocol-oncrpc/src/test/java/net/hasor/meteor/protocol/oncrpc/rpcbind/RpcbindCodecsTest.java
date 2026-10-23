/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;
import org.junit.Test;
import static org.junit.Assert.*;

public class RpcbindCodecsTest {
    @Test
    public void version2StructureUsesFourUnsignedWords() {
        // pmap 每个字段都是 unsigned int，不使用 Java long 的默认 hyper 编码。
        XdrCodec<PortMapperMapping> codec = this.codec(PortMapperService.class, PortMapperMapping.class);
        PortMapperMapping value = new PortMapperMapping(0xffffffffL, 4, 6, 65535);
        byte[] encoded = this.encode(codec, value);
        assertEquals(16, encoded.length);

        // 每个字段的 unsigned 语义在往返后保持不变。
        PortMapperMapping decoded = this.decode(codec, encoded);
        assertEquals(0xffffffffL, decoded.getProgram());
        assertEquals(4, decoded.getVersion());
        assertEquals(6, decoded.getProtocol());
        assertEquals(65535, decoded.getPort());
    }

    @Test
    public void version3StructurePreservesUniversalAddressAndOwner() {
        // rpcb 的 netid、通用地址与 owner 各自是 XDR string。
        XdrCodec<RpcbindMapping> codec = this.codec(RpcbindV3Service.class, RpcbindMapping.class);
        RpcbindMapping value = new RpcbindMapping(100000, 3, "tcp6", "::1.31.187", "local-owner");
        RpcbindMapping decoded = this.decode(codec, this.encode(codec, value));
        assertEquals(100000, decoded.getProgram());
        assertEquals(3, decoded.getVersion());
        assertEquals("tcp6", decoded.getNetid());
        assertEquals("::1.31.187", decoded.getAddress());
        assertEquals("local-owner", decoded.getOwner());
    }

    @Test
    public void emptyDumpUsesFalsePointer() {
        // 空 pmaplist 只有 false 指针，不能写成带数量的数组后再多写字段。
        XdrCodec<PortMapperMapping[]> codec = this.codec(PortMapperService.class, PortMapperMapping[].class);
        byte[] encoded = this.encode(codec, new PortMapperMapping[0]);
        assertArrayEquals(new byte[4], encoded);
        assertEquals(0, this.decode(codec, encoded).length);
    }

    @Test
    public void portDumpUsesBooleanPointersBetweenMappings() {
        // 每个节点前有 true，最后一个节点后有 false。
        XdrCodec<PortMapperMapping[]> codec = this.codec(PortMapperService.class, PortMapperMapping[].class);
        PortMapperMapping[] mappings = { new PortMapperMapping(1, 2, 6, 8123), new PortMapperMapping(1, 2, 17, 8124) };
        byte[] encoded = this.encode(codec, mappings);
        assertEquals(44, encoded.length);
        PortMapperMapping[] decoded = this.decode(codec, encoded);
        assertEquals(2, decoded.length);
        assertEquals(8123, decoded[0].getPort());
        assertEquals(8124, decoded[1].getPort());
    }

    @Test
    public void rpcbindDumpRetainsIndependentTransportMappings() {
        // 相同 program/version 的两个 netid 仍是不同节点。
        XdrCodec<RpcbindMapping[]> codec = this.codec(RpcbindV4Service.class, RpcbindMapping[].class);
        RpcbindMapping tcp = new RpcbindMapping(1, 2, "tcp", "127.0.0.1.31.187", "owner");
        RpcbindMapping udp = new RpcbindMapping(1, 2, "udp", "127.0.0.1.31.188", "owner");
        RpcbindMapping[] decoded = this.decode(codec, this.encode(codec, new RpcbindMapping[] { tcp, udp }));
        assertEquals("tcp", decoded[0].getNetid());
        assertEquals("udp", decoded[1].getNetid());
    }

    @Test
    public void truncatedDumpPointerIsRejected() {
        // 有节点的指针后面不能缺少节点正文。
        XdrCodec<PortMapperMapping[]> codec = this.codec(PortMapperService.class, PortMapperMapping[].class);
        byte[] truncated = { 0, 0, 0, 1 };
        assertThrows(IllegalArgumentException.class, () -> this.decode(codec, truncated));
    }

    @Test
    public void oversizedDumpIsRejectedBeforeEncoding() {
        // 指针链表有独立节点数量上限，不能无限增长。
        XdrCodec<RpcbindMapping[]> codec = this.codec(RpcbindV4Service.class, RpcbindMapping[].class);
        RpcbindMapping[] oversized = new RpcbindMapping[4097];
        assertThrows(IllegalArgumentException.class, () -> this.encode(codec, oversized));
    }

    @Test
    public void oversizedTextIsRejected() {
        // 远端提供的 rpcbind 文本仍受本地限制。
        XdrCodec<RpcbindMapping> codec = this.codec(RpcbindV3Service.class, RpcbindMapping.class);
        RpcbindMapping oversized = new RpcbindMapping(1, 1, "tcp", "x".repeat(1025), "owner");
        assertThrows(IllegalArgumentException.class, () -> this.encode(codec, oversized));
    }

    @Test
    public void arbitraryBusinessInterfaceIsNotRpcbindContract() {
        // 此工具只配置其公开的标准控制契约。
        assertThrows(IllegalArgumentException.class, () -> RpcbindCodecs.resource(Runnable.class));
    }

    @Test
    public void version4RetainsInheritedProceduresAndExactLookup() {
        // 版本 4 保留版本 3 的控制过程，并额外声明过程 9。
        OncRpcServiceResource resource = RpcbindCodecs.resource(RpcbindV4Service.class);
        assertEquals(100000, resource.getProgram());
        assertEquals(4, resource.getVersion());
        assertEquals("getAddress", resource.getMethods().get(3L).getName());
        assertEquals("getVersionAddress", resource.getMethods().get(9L).getName());
    }

    @SuppressWarnings("unchecked")
    private <T> XdrCodec<T> codec(Class<?> service, Class<T> type) {
        return (XdrCodec<T>) RpcbindCodecs.resource(service).getCodec(type);
    }

    private <T> byte[] encode(XdrCodec<T> codec, T value) {
        XdrOutput output = new XdrOutput(1024 * 1024, 10000, 64);
        output.write(codec, value);
        return output.toByteArray();
    }

    private <T> T decode(XdrCodec<T> codec, byte[] encoded) {
        XdrInput input = new XdrInput(encoded, 1024 * 1024, 10000, 64);
        T value = input.read(codec);
        input.requireEnd();
        return value;
    }
}
