/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetField;
import java.util.List;
import java.util.Map;

/** 普通 DTO 不继承 Thrift SDK 类型，字段声明顺序刻意与编号不同。 */
public class Note {
    @MetField(id = 7)
    private String text;
    @MetField(id = 1)
    private long id;
    @MetField(id = 3)
    private Integer priority;
    @MetField(id = 4)
    private Map<String, List<Note>> children;

    public Note() {
    }

    public String getText() { return this.text; }
    public void setText(String text) { this.text = text; }
    public long getId() { return this.id; }
    public void setId(long id) { this.id = id; }
    public Integer getPriority() { return this.priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Map<String, List<Note>> getChildren() { return this.children; }
    public void setChildren(Map<String, List<Note>> children) { this.children = children; }
}
