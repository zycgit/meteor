/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import java.math.BigInteger;
import java.util.List;

/** 与业务 IDL 对应的普通 Java 数据对象。 */
public class OncRpcSample {
    private int            signedValue;
    private long           unsignedValue;
    private long           signedHyper;
    private BigInteger     unsignedHyper;
    private float          floatValue;
    private double         doubleValue;
    private boolean        boolValue;
    private String         text;
    private byte[]         fixedOpaque;
    private byte[]         bytes;
    private List<Integer>  fixedNumbers;
    private List<Integer>  numbers;
    private int            colour;
    private OncRpcChoice   choice;
    private OncRpcOptional optional;

    public int getSignedValue() {
        return this.signedValue;
    }

    public void setSignedValue(int signedValue) {
        this.signedValue = signedValue;
    }

    public long getUnsignedValue() {
        return this.unsignedValue;
    }

    public void setUnsignedValue(long unsignedValue) {
        this.unsignedValue = unsignedValue;
    }

    public long getSignedHyper() {
        return this.signedHyper;
    }

    public void setSignedHyper(long signedHyper) {
        this.signedHyper = signedHyper;
    }

    public BigInteger getUnsignedHyper() {
        return this.unsignedHyper;
    }

    public void setUnsignedHyper(BigInteger unsignedHyper) {
        this.unsignedHyper = unsignedHyper;
    }

    public float getFloatValue() {
        return this.floatValue;
    }

    public void setFloatValue(float floatValue) {
        this.floatValue = floatValue;
    }

    public double getDoubleValue() {
        return this.doubleValue;
    }

    public void setDoubleValue(double doubleValue) {
        this.doubleValue = doubleValue;
    }

    public boolean getBoolValue() {
        return this.boolValue;
    }

    public void setBoolValue(boolean boolValue) {
        this.boolValue = boolValue;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public byte[] getFixedOpaque() {
        return this.fixedOpaque;
    }

    public void setFixedOpaque(byte[] fixedOpaque) {
        this.fixedOpaque = fixedOpaque;
    }

    public byte[] getBytes() {
        return this.bytes;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public List<Integer> getFixedNumbers() {
        return this.fixedNumbers;
    }

    public void setFixedNumbers(List<Integer> fixedNumbers) {
        this.fixedNumbers = fixedNumbers;
    }

    public List<Integer> getNumbers() {
        return this.numbers;
    }

    public void setNumbers(List<Integer> numbers) {
        this.numbers = numbers;
    }

    public int getColour() {
        return this.colour;
    }

    public void setColour(int colour) {
        this.colour = colour;
    }

    public OncRpcChoice getChoice() {
        return this.choice;
    }

    public void setChoice(OncRpcChoice choice) {
        this.choice = choice;
    }

    public OncRpcOptional getOptional() {
        return this.optional;
    }

    public void setOptional(OncRpcOptional optional) {
        this.optional = optional;
    }
}
