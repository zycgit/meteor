/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodecs;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;

/** RFC 1833's two mapping structures and their pointer lists; these lists are not counted XDR arrays. */
public final class RpcbindCodecs {
    private static final int                         MAX_MAPPINGS = 4096;
    private static final int                         MAX_TEXT     = 1024;
    private static final XdrCodec<PortMapperMapping> PORT_MAPPING = codec(input -> {
        long program = input.readUnsignedInt();
        long version = input.readUnsignedInt();
        long protocol = input.readUnsignedInt();
        long port = input.readUnsignedInt();
        return new PortMapperMapping(program, version, protocol, port);
    }, (output, mapping) -> {
        output.writeUnsignedInt(mapping.getProgram());
        output.writeUnsignedInt(mapping.getVersion());
        output.writeUnsignedInt(mapping.getProtocol());
        output.writeUnsignedInt(mapping.getPort());
    });
    private static final XdrCodec<RpcbindMapping>    RPC_MAPPING  = codec(input -> {
        long program = input.readUnsignedInt();
        long version = input.readUnsignedInt();
        String netid = input.readString(MAX_TEXT);
        String address = input.readString(MAX_TEXT);
        String owner = input.readString(MAX_TEXT);
        return new RpcbindMapping(program, version, netid, address, owner);
    }, (output, mapping) -> {
        output.writeUnsignedInt(mapping.getProgram());
        output.writeUnsignedInt(mapping.getVersion());
        output.writeString(mapping.getNetid(), MAX_TEXT);
        output.writeString(mapping.getAddress(), MAX_TEXT);
        output.writeString(mapping.getOwner(), MAX_TEXT);
    });

    private RpcbindCodecs() {
    }

    /** Bind these schemas when directly publishing or subscribing one of the supplied standard control contracts. */
    public static OncRpcServiceResource resource(Class<?> type) {
        if (type != PortMapperService.class && type != RpcbindV3Service.class && type != RpcbindV4Service.class) {
            throw new IllegalArgumentException("Unsupported rpcbind control contract");
        }
        OncRpcServiceResource resource = new OncRpcServiceResource(type);
        resource.codec(long.class, XdrCodecs.UNSIGNED_INT);
        resource.codec(PortMapperMapping.class, PORT_MAPPING);
        resource.codec(RpcbindMapping.class, RPC_MAPPING);
        resource.codec(PortMapperMapping[].class, pointerList(PORT_MAPPING, PortMapperMapping[]::new));
        resource.codec(RpcbindMapping[].class, pointerList(RPC_MAPPING, RpcbindMapping[]::new));
        return resource;
    }

    private static <T> XdrCodec<T[]> pointerList(XdrCodec<T> element, IntFunction<T[]> array) {
        return codec(input -> {
            // rpcb_list and pmaplist end in a false pointer, including an empty list.
            List<T> values = new ArrayList<>();
            while (input.readBoolean()) {
                if (values.size() == MAX_MAPPINGS) {
                    throw new IllegalArgumentException("rpcbind dump exceeds mapping limit");
                }
                values.add(input.read(element));
            }
            return values.toArray(array.apply(values.size()));
        }, (output, values) -> {
            if (values == null || values.length > MAX_MAPPINGS) {
                throw new IllegalArgumentException("rpcbind dump exceeds mapping limit");
            }
            for (T value : values) {
                output.writeBoolean(true);
                output.write(element, value);
            }
            output.writeBoolean(false);
        });
    }

    private static <T> XdrCodec<T> codec(Function<XdrInput, T> read, BiConsumer<XdrOutput, T> write) {
        return new XdrCodec<>() {
            @Override
            public T read(XdrInput input) {
                return read.apply(input);
            }

            @Override
            public void write(XdrOutput output, T value) {
                write.accept(output, value);
            }
        };
    }
}
