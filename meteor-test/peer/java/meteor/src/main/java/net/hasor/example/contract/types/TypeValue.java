/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.annotation.MetField;

/** 各语言共用的基础类型契约，编号与 Protobuf、Thrift、XDR 的映射保持固定。 */
public class TypeValue implements Serializable {
    private static final long serialVersionUID = 1L;

    @MetField(id = 1)
    private String caseId;

    @MetField(id = 2)
    private boolean booleanValue;

    @MetField(id = 3)
    private byte int8Value;

    @MetField(id = 4)
    private short int16Value;

    @MetField(id = 5)
    private int int32Value;

    @MetField(id = 6)
    private long int64Value;

    @MetField(id = 7)
    private float float32Value;

    @MetField(id = 8)
    private double float64Value;

    @MetField(id = 9)
    private String textValue;

    @MetField(id = 10)
    private byte[] binaryValue;

    @MetField(id = 11)
    private TypeKind enumValue;

    @MetField(id = 12)
    private List<String> listValue;

    @MetField(id = 13)
    private Map<String, Long> mapValue;

    @MetField(id = 14)
    private TypeRecord recordValue;

    @MetField(id = 15)
    private String optionalValue;

    @MetField(id = 16)
    private String receipt;

    @MetField(id = 17)
    private BigInteger unsigned64Value;

    public String getCaseId() {
        return this.caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public boolean isBooleanValue() {
        return this.booleanValue;
    }

    public void setBooleanValue(boolean booleanValue) {
        this.booleanValue = booleanValue;
    }

    public byte getInt8Value() {
        return this.int8Value;
    }

    public void setInt8Value(byte int8Value) {
        this.int8Value = int8Value;
    }

    public short getInt16Value() {
        return this.int16Value;
    }

    public void setInt16Value(short int16Value) {
        this.int16Value = int16Value;
    }

    public int getInt32Value() {
        return this.int32Value;
    }

    public void setInt32Value(int int32Value) {
        this.int32Value = int32Value;
    }

    public long getInt64Value() {
        return this.int64Value;
    }

    public void setInt64Value(long int64Value) {
        this.int64Value = int64Value;
    }

    public float getFloat32Value() {
        return this.float32Value;
    }

    public void setFloat32Value(float float32Value) {
        this.float32Value = float32Value;
    }

    public double getFloat64Value() {
        return this.float64Value;
    }

    public void setFloat64Value(double float64Value) {
        this.float64Value = float64Value;
    }

    public String getTextValue() {
        return this.textValue;
    }

    public void setTextValue(String textValue) {
        this.textValue = textValue;
    }

    public byte[] getBinaryValue() {
        return this.binaryValue;
    }

    public void setBinaryValue(byte[] binaryValue) {
        this.binaryValue = binaryValue;
    }

    public TypeKind getEnumValue() {
        return this.enumValue;
    }

    public void setEnumValue(TypeKind enumValue) {
        this.enumValue = enumValue;
    }

    public List<String> getListValue() {
        return this.listValue;
    }

    public void setListValue(List<String> listValue) {
        this.listValue = listValue;
    }

    public Map<String, Long> getMapValue() {
        return this.mapValue;
    }

    public void setMapValue(Map<String, Long> mapValue) {
        this.mapValue = mapValue;
    }

    public TypeRecord getRecordValue() {
        return this.recordValue;
    }

    public void setRecordValue(TypeRecord recordValue) {
        this.recordValue = recordValue;
    }

    public String getOptionalValue() {
        return this.optionalValue;
    }

    public void setOptionalValue(String optionalValue) {
        this.optionalValue = optionalValue;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }

    public BigInteger getUnsigned64Value() {
        return this.unsigned64Value;
    }

    public void setUnsigned64Value(BigInteger unsigned64Value) {
        this.unsigned64Value = unsigned64Value;
    }
}
