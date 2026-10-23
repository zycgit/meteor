/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex.onc;
import java.util.*;
import net.hasor.example.contract.complex.ComplexCustomer;
import net.hasor.example.contract.complex.ComplexDocument;
import net.hasor.example.contract.complex.ComplexIndex;
import net.hasor.example.contract.complex.ComplexLine;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodecs;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;

/** 按 values.x 的字段顺序编码；结构、集合和条目均通过 XDR 容器执行深度与数量限制。 */
public final class OncComplexCodecs {
    private static final int                                MAX_TEXT    = 256;
    private static final int                                MAX_ENTRIES = 64;
    private static final XdrCodec<String>                   TEXT        = XdrCodecs.string(MAX_TEXT);
    private static final XdrCodec<ComplexCustomer>          CUSTOMER    = new XdrCodec<>() {
        @Override
        public ComplexCustomer read(XdrInput input) {
            ComplexCustomer value = new ComplexCustomer();
            value.setCode(input.read(TEXT));
            value.setName(input.read(TEXT));
            value.setVerified(input.readBoolean());
            value.setRating(input.readDouble());
            return value;
        }

        @Override
        public void write(XdrOutput output, ComplexCustomer value) {
            output.write(TEXT, value.getCode());
            output.write(TEXT, value.getName());
            output.writeBoolean(value.isVerified());
            output.writeDouble(value.getRating());
        }
    };
    private static final XdrCodec<ComplexLine>              LINE        = new XdrCodec<>() {
        @Override
        public ComplexLine read(XdrInput input) {
            ComplexLine value = new ComplexLine();
            value.setSku(input.read(TEXT));
            value.setQuantity(input.readInt());
            value.setUnitPrice(input.readDouble());
            return value;
        }

        @Override
        public void write(XdrOutput output, ComplexLine value) {
            output.write(TEXT, value.getSku());
            output.writeInt(value.getQuantity());
            output.writeDouble(value.getUnitPrice());
        }
    };
    private static final XdrCodec<List<ComplexLine>>        LINES       = XdrCodecs.list(LINE, MAX_ENTRIES);
    private static final XdrCodec<List<String>>             ROUTE       = XdrCodecs.list(TEXT, MAX_ENTRIES);
    private static final XdrCodec<Map<String, ComplexLine>> LINE_MAP    = map(LINE);
    private static final XdrCodec<Map<String, Integer>>     STOCK_MAP   = map(XdrCodecs.INT);
    private static final XdrCodec<ComplexDocument>          DOCUMENT    = new XdrCodec<>() {
        @Override
        public ComplexDocument read(XdrInput input) {
            ComplexDocument value = new ComplexDocument();
            value.setDocumentId(input.readInt());
            value.setCustomer(input.read(CUSTOMER));
            value.setLines(input.read(LINES));
            value.setRoute(input.read(ROUTE));
            value.setReceipt(input.read(TEXT));
            return value;
        }

        @Override
        public void write(XdrOutput output, ComplexDocument value) {
            output.writeInt(value.getDocumentId());
            output.write(CUSTOMER, value.getCustomer());
            output.write(LINES, value.getLines());
            output.write(ROUTE, value.getRoute());
            output.write(TEXT, value.getReceipt());
        }
    };
    private static final XdrCodec<ComplexIndex>             INDEX       = new XdrCodec<>() {
        @Override
        public ComplexIndex read(XdrInput input) {
            ComplexIndex value = new ComplexIndex();
            value.setRevision(input.readInt());
            value.setLinesByCode(input.read(LINE_MAP));
            value.setStockByCode(input.read(STOCK_MAP));
            value.setReceipt(input.read(TEXT));
            return value;
        }

        @Override
        public void write(XdrOutput output, ComplexIndex value) {
            output.writeInt(value.getRevision());
            output.write(LINE_MAP, value.getLinesByCode());
            output.write(STOCK_MAP, value.getStockByCode());
            output.write(TEXT, value.getReceipt());
        }
    };

    private OncComplexCodecs() {
    }

    public static OncRpcServiceResource resource() {
        return new OncRpcServiceResource(OncComplexService.class) //
                .codec(ComplexDocument.class, DOCUMENT) //
                .codec(ComplexIndex.class, INDEX);
    }

    private static <T> XdrCodec<Map<String, T>> map(XdrCodec<T> valueCodec) {
        // XDR 没有 Map：按 IDL 的有界 key/value 条目数组保留迭代顺序。
        XdrCodec<Map.Entry<String, T>> entryCodec = new XdrCodec<>() {
            @Override
            public Map.Entry<String, T> read(XdrInput input) {
                String key = input.read(TEXT);
                return new AbstractMap.SimpleImmutableEntry<>(key, input.read(valueCodec));
            }

            @Override
            public void write(XdrOutput output, Map.Entry<String, T> entry) {
                output.write(TEXT, entry.getKey());
                output.write(valueCodec, entry.getValue());
            }
        };
        XdrCodec<List<Map.Entry<String, T>>> entriesCodec = XdrCodecs.list(entryCodec, MAX_ENTRIES);
        return new XdrCodec<>() {
            @Override
            public Map<String, T> read(XdrInput input) {
                Map<String, T> values = new LinkedHashMap<>();
                for (Map.Entry<String, T> entry : input.read(entriesCodec)) {
                    if (values.containsKey(entry.getKey())) {
                        throw new IllegalArgumentException("Duplicate XDR Map key");
                    }
                    values.put(entry.getKey(), entry.getValue());
                }
                return values;
            }

            @Override
            public void write(XdrOutput output, Map<String, T> values) {
                output.write(entriesCodec, new ArrayList<>(values.entrySet()));
            }
        };
    }
}
