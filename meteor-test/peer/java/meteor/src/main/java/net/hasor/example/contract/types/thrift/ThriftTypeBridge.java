/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types.thrift;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeKind;
import net.hasor.example.contract.types.TypeRecord;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.thrift.generated.TypeEchoService.Iface;
import org.apache.thrift.TException;

/** IDL 显式用 double 承载 float32、十进制 string 承载 uint64，业务仍读取真实 Java 类型。 */
public final class ThriftTypeBridge implements Iface {
    private static final BigInteger      MAX_UNSIGNED = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private final        TypeEchoService business;

    public ThriftTypeBridge(TypeEchoService business) {
        this.business = business;
    }

    @Override
    public net.hasor.example.contract.types.thrift.generated.TypeValue echo(net.hasor.example.contract.types.thrift.generated.TypeValue request) {
        // 协议桥只恢复类型，业务收据由统一服务实现创建。
        TypeValue response = this.business.echo(fromThrift(request));
        return toThrift(response);
    }

    public static TypeEchoService remote(Iface remote) {
        return request -> {
            try {
                return fromThrift(remote.echo(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift type call failed", failure);
            }
        };
    }

    private static net.hasor.example.contract.types.thrift.generated.TypeValue toThrift(TypeValue source) {
        // 每个标量以 IDL 的固定线类型赋值，整数不通过 double 中转。
        var result = new net.hasor.example.contract.types.thrift.generated.TypeValue();
        result.setCaseId(source.getCaseId());
        result.setBooleanValue(source.isBooleanValue());
        result.setInt8Value(source.getInt8Value());
        result.setInt16Value(source.getInt16Value());
        result.setInt32Value(source.getInt32Value());
        result.setInt64Value(source.getInt64Value());
        result.setFloat32Value(source.getFloat32Value());
        result.setFloat64Value(source.getFloat64Value());
        result.setTextValue(source.getTextValue());
        int enumId = source.getEnumValue().ordinal();
        var wireKind = net.hasor.example.contract.types.thrift.generated.TypeKind.findByValue(enumId);
        result.setEnumValue(wireKind);
        result.setReceipt(source.getReceipt());
        result.setOptionalValue(source.getOptionalValue());

        // uint64 使用明确的十进制契约，拒绝超范围值而不是截断低 64 位。
        BigInteger unsigned = source.getUnsigned64Value();
        if (unsigned.signum() < 0 || unsigned.compareTo(MAX_UNSIGNED) > 0) {
            throw new IllegalArgumentException("Thrift unsigned decimal mapping is outside uint64 range");
        }
        result.setUnsigned64Value(unsigned.toString());

        // 二进制、集合和嵌套 DTO 均建立独立的协议对象。
        if (source.getBinaryValue() != null) {
            result.setBinaryValue(source.getBinaryValue().clone());
        }
        if (source.getListValue() != null) {
            result.setListValue(new ArrayList<>(source.getListValue()));
        }
        if (source.getMapValue() != null) {
            result.setMapValue(new LinkedHashMap<>(source.getMapValue()));
        }
        TypeRecord record = source.getRecordValue();
        if (record != null) {
            var wireRecord = new net.hasor.example.contract.types.thrift.generated.TypeRecord();
            wireRecord.setIndex(record.getIndex());
            wireRecord.setName(record.getName());
            result.setRecordValue(wireRecord);
        }
        return result;
    }

    private static TypeValue fromThrift(net.hasor.example.contract.types.thrift.generated.TypeValue source) {
        // float32 的显式 double 映射必须精确可逆，负零的符号位同样保留。
        double wireSingle = source.getFloat32Value();
        float single = (float) wireSingle;
        long expectedBits = Double.doubleToRawLongBits(wireSingle);
        long restoredBits = Double.doubleToRawLongBits((double) single);
        if (expectedBits != restoredBits) {
            throw new IllegalArgumentException("Thrift double cannot narrow exactly to float32");
        }
        TypeValue result = new TypeValue();
        result.setCaseId(source.getCaseId());
        result.setBooleanValue(source.isBooleanValue());
        result.setInt8Value(source.getInt8Value());
        result.setInt16Value(source.getInt16Value());
        result.setInt32Value(source.getInt32Value());
        result.setInt64Value(source.getInt64Value());
        result.setFloat32Value(single);
        result.setFloat64Value(source.getFloat64Value());
        result.setTextValue(source.getTextValue());
        result.setEnumValue(TypeKind.values()[source.getEnumValue().getValue()]);
        result.setOptionalValue(source.isSetOptionalValue() ? source.getOptionalValue() : null);
        result.setReceipt(source.getReceipt());

        // uint64 的恢复使用 BigInteger，超出 Java signed long 的值也能完整验证。
        BigInteger unsigned = new BigInteger(source.getUnsigned64Value());
        if (unsigned.signum() < 0 || unsigned.compareTo(MAX_UNSIGNED) > 0) {
            throw new IllegalArgumentException("Thrift unsigned decimal mapping is outside uint64 range");
        }
        result.setUnsigned64Value(unsigned);

        // 反向转换同样不借用 SDK 对象中可变的集合和二进制字段。
        if (source.getBinaryValue() != null) {
            result.setBinaryValue(source.getBinaryValue().clone());
        }
        if (source.getListValue() != null) {
            result.setListValue(new ArrayList<>(source.getListValue()));
        }
        if (source.getMapValue() != null) {
            result.setMapValue(new LinkedHashMap<>(source.getMapValue()));
        }
        var record = source.getRecordValue();
        if (record != null) {
            TypeRecord businessRecord = new TypeRecord();
            businessRecord.setIndex(record.getIndex());
            businessRecord.setName(record.getName());
            result.setRecordValue(businessRecord);
        }
        return result;
    }
}
