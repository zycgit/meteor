/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 逐项读取真实声明类型并建立独立响应，用收据证明请求进入了业务服务。 */
public class TypeEchoServiceImpl implements TypeEchoService {
    @Override
    public TypeValue echo(TypeValue request) {
        // 标量按声明类型读取，不通过字符串化或浮点中转恢复整数。
        TypeValue response = new TypeValue();
        response.setCaseId(request.getCaseId());
        response.setBooleanValue(request.isBooleanValue());
        response.setInt8Value(request.getInt8Value());
        response.setInt16Value(request.getInt16Value());
        response.setInt32Value(request.getInt32Value());
        response.setInt64Value(request.getInt64Value());
        response.setFloat32Value(request.getFloat32Value());
        response.setFloat64Value(request.getFloat64Value());
        response.setTextValue(request.getTextValue());
        response.setEnumValue(request.getEnumValue());
        response.setOptionalValue(request.getOptionalValue());
        response.setUnsigned64Value(request.getUnsigned64Value());

        // 集合、二进制和嵌套结构属于本次响应，避免借用原请求中的可变对象。
        byte[] binary = request.getBinaryValue();
        if (binary != null) {
            response.setBinaryValue(binary.clone());
        }
        if (request.getListValue() != null) {
            response.setListValue(new ArrayList<>(request.getListValue()));
        }
        if (request.getMapValue() != null) {
            response.setMapValue(new LinkedHashMap<>(request.getMapValue()));
        }
        TypeRecord inputRecord = request.getRecordValue();
        if (inputRecord != null) {
            TypeRecord outputRecord = new TypeRecord();
            outputRecord.setIndex(inputRecord.getIndex());
            outputRecord.setName(inputRecord.getName());
            response.setRecordValue(outputRecord);
        }

        // 业务收据由提供者生成，客户端不能原样回显自己的 receipt 通过验证。
        response.setReceipt("accepted:" + request.getCaseId());
        return response;
    }
}
