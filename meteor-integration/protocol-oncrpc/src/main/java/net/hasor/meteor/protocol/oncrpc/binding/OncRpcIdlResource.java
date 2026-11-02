/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.oncrpc.definition.OncRpcIdlProcedure;
import net.hasor.meteor.protocol.oncrpc.definition.OncRpcIdlProgram;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlDocument;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlParser;

/** 本地 RPC/XDR 文件入口；不运行 C 预处理器、编译器或业务实现。 */
public final class OncRpcIdlResource {
    final   XdrIdlDocument document;
    private String         programName;
    private Long           version;

    public OncRpcIdlResource(URL source) throws IOException {
        this.document = XdrIdlParser.load(source);
    }

    /** 多程序或多版本文件通过明确符号和版本号选择。 */
    public OncRpcIdlResource selectProgram(String name, long version) {
        if (version <= 0 || version > 0xffffffffL) {
            throw new IllegalArgumentException("RPC version must be nonzero unsigned 32-bit");
        }
        this.programName = name;
        this.version = version;
        return this;
    }

    /** 保留原始文件，描述发布不依赖运行期 Java 对象。 */
    public Map<String, String> getSources() {
        String path = this.document.source.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return Map.of(name, this.document.text);
    }

    OncRpcIdlProgram program(Class<?> service) {
        long id = ServiceMetaParser.parse(service).getContractServiceId();
        OncRpcIdlProgram selected = null;
        for (OncRpcIdlProgram program : this.document.programs.values()) {
            if (this.programName != null && !StringUtils.equals(program.name, this.programName)) {
                continue;
            }
            if (id != -1L && program.id != id) {
                continue;
            }
            if (selected != null) {
                throw this.error("Select one RPC program explicitly");
            }
            selected = program;
        }
        if (selected == null) {
            throw this.error("RPC program selection or annotation does not match the file");
        }
        return selected;
    }

    long version(OncRpcIdlProgram program) {
        // 文件版本只取自显式选择器或唯一声明，不读取公共服务的字符串版本。
        Long selected = this.version;
        if (selected == null && program.versions.size() == 1) {
            selected = program.versions.keySet().iterator().next();
        }
        if (selected == null || !program.versions.containsKey(selected)) {
            throw this.error("RPC version selection does not match the file");
        }
        return selected;
    }

    OncRpcIdlProcedure procedure(Map<String, OncRpcIdlProcedure> procedures, Method method) {
        MethodMeta mapping = ServiceMetaParser.method(method);
        boolean named = mapping.getDeclaredName() != null;
        String name = mapping.getName();
        OncRpcIdlProcedure selected = null;
        if (mapping.getMethodId() != 0) {
            for (OncRpcIdlProcedure procedure : procedures.values()) {
                if (procedure.id == mapping.getMethodId()) {
                    selected = procedure;
                }
            }
        } else {
            selected = procedures.get(name);
        }
        if (selected == null || selected.id == 0 || named && !StringUtils.equals(selected.name, name)) {
            throw this.error("RPC method name/id does not match the file: " + method);
        }
        return selected;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(this.document.source + ": " + message);
    }
}
