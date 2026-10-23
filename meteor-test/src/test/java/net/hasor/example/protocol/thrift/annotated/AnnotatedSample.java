/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.hasor.meteor.annotation.MetField;

/** 与 interop.thrift 的 Sample 对应，保持普通 Java DTO，不继承生成类。 */
public class AnnotatedSample {
    @MetField(id = 2)
    private String              text;
    @MetField(id = 1)
    private long                id;
    @MetField(id = 3)
    private boolean             enabled;
    @MetField(id = 4)
    private short               small;
    @MetField(id = 5)
    private int                 count;
    @MetField(id = 6)
    private double              ratio;
    @MetField(id = 7)
    private byte[]              payload;
    @MetField(id = 8)
    private List<String>        tags;
    @MetField(id = 9)
    private Map<String, String> labels;
    @MetField(id = 10)
    private Set<Integer>        codes;
    @MetField(id = 11)
    private byte                tiny;
    @MetField(id = 12)
    private String              note;
    @MetField(id = 13)
    private Integer             optionalCount;
    @MetField(id = 14)
    private int                 defaultCount = 17;
    @MetField(id = 15)
    private AnnotatedShade      shade        = AnnotatedShade.BLUE;
    @MetField(id = 16)
    private AnnotatedChoice     choice;
    @MetField(id = 17)
    private UUID                requestId;

    public AnnotatedSample() {
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public long getId() {
        return this.id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public boolean getEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public short getSmall() {
        return this.small;
    }

    public void setSmall(short small) {
        this.small = small;
    }

    public int getCount() {
        return this.count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public double getRatio() {
        return this.ratio;
    }

    public void setRatio(double ratio) {
        this.ratio = ratio;
    }

    public byte[] getPayload() {
        return this.payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public List<String> getTags() {
        return this.tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Map<String, String> getLabels() {
        return this.labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public Set<Integer> getCodes() {
        return this.codes;
    }

    public void setCodes(Set<Integer> codes) {
        this.codes = codes;
    }

    public byte getTiny() {
        return this.tiny;
    }

    public void setTiny(byte tiny) {
        this.tiny = tiny;
    }

    public String getNote() {
        return this.note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getOptionalCount() {
        return this.optionalCount;
    }

    public void setOptionalCount(Integer optionalCount) {
        this.optionalCount = optionalCount;
    }

    public int getDefaultCount() {
        return this.defaultCount;
    }

    public void setDefaultCount(int defaultCount) {
        this.defaultCount = defaultCount;
    }

    public AnnotatedShade getShade() {
        return this.shade;
    }

    public void setShade(AnnotatedShade shade) {
        this.shade = shade;
    }

    public AnnotatedChoice getChoice() {
        return this.choice;
    }

    public void setChoice(AnnotatedChoice choice) {
        this.choice = choice;
    }

    public UUID getRequestId() {
        return this.requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }
}
