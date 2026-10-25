/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex.thrift;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.example.contract.complex.*;
import net.hasor.example.contract.complex.thrift.generated.*;
import org.apache.thrift.TException;

/** 只在 IDL 边界转换字段；处理规则和收据均由共享业务服务负责。 */
public final class ThriftComplexBridge implements ThriftComplexValueService.Iface {
    private final ComplexValueService business;

    public ThriftComplexBridge(ComplexValueService business) {
        this.business = business;
    }

    @Override
    public ThriftComplexDocument acceptDocument(ThriftComplexDocument request) {
        return toThrift(this.business.acceptDocument(fromThrift(request)));
    }

    @Override
    public ThriftComplexIndex acceptIndex(ThriftComplexIndex request) {
        return toThrift(this.business.acceptIndex(fromThrift(request)));
    }

    public static ComplexValueService remote(ThriftComplexValueService.Iface remote) {
        return new Client(remote);
    }

    private static ThriftComplexDocument toThrift(ComplexDocument source) {
        // 按声明类型转换每个嵌套对象和列表元素，不通过 JSON 或无类型 Map 中转。
        ThriftComplexCustomer customer = new ThriftComplexCustomer();
        customer.setCode(source.getCustomer().getCode());
        customer.setName(source.getCustomer().getName());
        customer.setVerified(source.getCustomer().isVerified());
        customer.setRating(source.getCustomer().getRating());
        List<ThriftComplexLine> lines = new ArrayList<>();
        for (ComplexLine line : source.getLines()) {
            lines.add(toThrift(line));
        }

        ThriftComplexDocument result = new ThriftComplexDocument();
        result.setDocumentId(source.getDocumentId());
        result.setCustomer(customer);
        result.setLines(lines);
        result.setRoute(new ArrayList<>(source.getRoute()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ComplexDocument fromThrift(ThriftComplexDocument source) {
        // 生成 DTO 的 required 字段已经由官方 reader 校验，业务仍接收原公共 DTO。
        ComplexCustomer customer = new ComplexCustomer();
        customer.setCode(source.getCustomer().getCode());
        customer.setName(source.getCustomer().getName());
        customer.setVerified(source.getCustomer().isVerified());
        customer.setRating(source.getCustomer().getRating());
        List<ComplexLine> lines = new ArrayList<>();
        for (ThriftComplexLine line : source.getLines()) {
            lines.add(fromThrift(line));
        }

        ComplexDocument result = new ComplexDocument();
        result.setDocumentId(source.getDocumentId());
        result.setCustomer(customer);
        result.setLines(lines);
        result.setRoute(new ArrayList<>(source.getRoute()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ThriftComplexIndex toThrift(ComplexIndex source) {
        // Map 保持 String key、struct value 和 i32 value 的显式 IDL 声明。
        Map<String, ThriftComplexLine> lines = new LinkedHashMap<>();
        for (Map.Entry<String, ComplexLine> entry : source.getLinesByCode().entrySet()) {
            lines.put(entry.getKey(), toThrift(entry.getValue()));
        }
        ThriftComplexIndex result = new ThriftComplexIndex();
        result.setRevision(source.getRevision());
        result.setLinesByCode(lines);
        result.setStockByCode(new LinkedHashMap<>(source.getStockByCode()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ComplexIndex fromThrift(ThriftComplexIndex source) {
        Map<String, ComplexLine> lines = new LinkedHashMap<>();
        for (Map.Entry<String, ThriftComplexLine> entry : source.getLinesByCode().entrySet()) {
            lines.put(entry.getKey(), fromThrift(entry.getValue()));
        }
        ComplexIndex result = new ComplexIndex();
        result.setRevision(source.getRevision());
        result.setLinesByCode(lines);
        result.setStockByCode(new LinkedHashMap<>(source.getStockByCode()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ThriftComplexLine toThrift(ComplexLine source) {
        ThriftComplexLine result = new ThriftComplexLine();
        result.setSku(source.getSku());
        result.setQuantity(source.getQuantity());
        result.setUnitPrice(source.getUnitPrice());
        return result;
    }

    private static ComplexLine fromThrift(ThriftComplexLine source) {
        ComplexLine result = new ComplexLine();
        result.setSku(source.getSku());
        result.setQuantity(source.getQuantity());
        result.setUnitPrice(source.getUnitPrice());
        return result;
    }

    private static final class Client implements ComplexValueService {
        private final ThriftComplexValueService.Iface remote;

        private Client(ThriftComplexValueService.Iface remote) {
            this.remote = remote;
        }

        @Override
        public ComplexDocument acceptDocument(ComplexDocument request) {
            try {
                return fromThrift(this.remote.acceptDocument(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift document call failed", failure);
            }
        }

        @Override
        public ComplexIndex acceptIndex(ComplexIndex request) {
            try {
                return fromThrift(this.remote.acceptIndex(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift index call failed", failure);
            }
        }
    }
}
