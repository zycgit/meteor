/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.StringUtils;

/** 读取 RPC/XDR IDL；C 预处理指令和透传代码需由调用方预先处理。 */
public final class XdrIdlParser {
    private final XdrIdlDocument  document;
    private final StreamTokenizer tokens;
    private       String          token;

    private XdrIdlParser(XdrIdlDocument document) throws IOException {
        this.document = document;
        this.tokens = new StreamTokenizer(new StringReader(document.text));
        this.tokens.resetSyntax();
        this.tokens.whitespaceChars(0, 32);
        this.tokens.wordChars('a', 'z');
        this.tokens.wordChars('A', 'Z');
        this.tokens.wordChars('0', '9');
        this.tokens.wordChars('_', '_');
        this.tokens.wordChars('-', '-');
        this.tokens.wordChars('+', '+');
        this.tokens.slashSlashComments(true);
        this.tokens.slashStarComments(true);
        this.document.constants.put("TRUE", 1L);
        this.document.constants.put("FALSE", 0L);
        this.next();
    }

    public static XdrIdlDocument load(URL source) throws IOException {
        String text;
        try (InputStream input = source.openStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        XdrIdlDocument document = new XdrIdlDocument(source, text);
        new XdrIdlParser(document).parse();
        return document;
    }

    private void parse() throws IOException {
        while (this.token != null) {
            if (this.take("const")) {
                String name = this.identifier();
                this.expect("=");
                long value = this.number();
                if (this.document.constants.putIfAbsent(name, value) != null) {
                    throw this.error("Duplicate constant: " + name);
                }
            } else if (this.take("typedef")) {
                XdrIdlMember declaration = this.member();
                XdrIdlType type = this.declare("typedef", declaration.name);
                type.element = declaration.type;
            } else if (this.take("struct")) {
                XdrIdlType type = this.declare("struct", this.identifier());
                this.expect("{");
                Set<String> names = new HashSet<>();
                while (!this.take("}")) {
                    XdrIdlMember member = this.member();
                    if (member.name.isEmpty() || !names.add(member.name)) {
                        throw this.error("Duplicate or unnamed struct member: " + member.name);
                    }
                    type.members.add(member);
                    this.expect(";");
                }
            } else if (this.take("enum")) {
                this.enumeration();
            } else if (this.take("union")) {
                this.union();
            } else if (this.take("program")) {
                this.program();
            } else {
                throw this.error("Unsupported declaration or unprocessed C directive: " + this.token);
            }
            this.expect(";");
        }
    }

    private void enumeration() throws IOException {
        XdrIdlType type = this.declare("enum", this.identifier());
        this.expect("{");
        while (!this.take("}")) {
            String name = this.identifier();
            this.expect("=");
            long number = this.number();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw this.error("Enum value exceeds signed 32-bit range: " + name);
            }
            if (type.enumeration.putIfAbsent(name, (int) number) != null) {
                throw this.error("Duplicate enum constant: " + name);
            }
            if (this.document.constants.putIfAbsent(name, number) != null) {
                throw this.error("Duplicate enum symbol: " + name);
            }
            if (!this.take(",") && !this.is("}")) {
                throw this.error("Expected ',' between enum constants");
            }
        }
    }

    private void union() throws IOException {
        XdrIdlType type = this.declare("union", this.identifier());
        this.expect("switch");
        this.expect("(");
        type.discriminator = this.member();
        this.expect(")");
        this.expect("{");
        Set<Long> labels = new HashSet<>();
        boolean defaultArm = false;
        while (!this.take("}")) {
            List<Long> cases = new ArrayList<>();
            while (this.take("case")) {
                long label = this.number();
                if (!labels.add(label)) {
                    throw this.error("Duplicate union case: " + label);
                }
                cases.add(label);
                this.expect(":");
            }
            boolean fallback = this.take("default");
            if (fallback) {
                if (defaultArm) {
                    throw this.error("Duplicate union default");
                }
                defaultArm = true;
                this.expect(":");
            }
            if (!fallback && cases.isEmpty()) {
                throw this.error("Expected union case or default");
            }
            XdrIdlMember member = this.member();
            member.cases.addAll(cases);
            member.defaultArm = fallback;
            type.members.add(member);
            this.expect(";");
        }
    }

    private void program() throws IOException {
        String name = this.identifier();
        this.expect("{");
        Map<Long, Map<String, OncRpcIdlProcedure>> versions = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        while (!this.take("}")) {
            this.expect("version");
            String versionName = this.identifier();
            this.expect("{");
            Map<String, OncRpcIdlProcedure> procedures = new LinkedHashMap<>();
            Set<Long> numbers = new HashSet<>();
            while (!this.take("}")) {
                XdrIdlType result = this.type();
                String procedureName = this.identifier();
                this.expect("(");
                XdrIdlType input = this.type();
                this.expect(")");
                this.expect("=");
                long id = this.unsigned();
                if (!numbers.add(id) || procedures.containsKey(procedureName)) {
                    throw this.error("Duplicate procedure: " + procedureName);
                }
                procedures.put(procedureName, new OncRpcIdlProcedure(procedureName, id, input, result));
                this.expect(";");
            }
            this.expect("=");
            long version = this.unsigned();
            if (version == 0 || versions.putIfAbsent(version, procedures) != null) {
                throw this.error("Duplicate or zero program version: " + version);
            }
            names.put(version, versionName);
            this.expect(";");
        }
        this.expect("=");
        OncRpcIdlProgram program = new OncRpcIdlProgram(name, this.unsigned());
        program.versions.putAll(versions);
        program.versionNames.putAll(names);
        if (versions.isEmpty() || this.document.programs.putIfAbsent(name, program) != null) {
            throw this.error("Duplicate or versionless program: " + name);
        }
    }

    private XdrIdlMember member() throws IOException {
        XdrIdlType type = this.type();
        if (StringUtils.equals(type.kind, "void")) {
            return new XdrIdlMember("", type);
        }
        if (this.take("*")) {
            XdrIdlType optional = new XdrIdlType("optional", "optional");
            optional.element = type;
            type = optional;
        }
        String name = this.identifier();
        boolean fixed = this.take("[");
        boolean variable = !fixed && this.take("<");
        if (fixed || variable) {
            String end = fixed ? "]" : ">";
            long length = this.is(end) ? -1 : this.unsigned();
            if (fixed && length < 0) {
                throw this.error("Fixed array requires a length");
            }
            this.expect(end);
            boolean inline = StringUtils.equals(type.kind, "string") || StringUtils.equals(type.kind, "opaque");
            if (inline) {
                if (fixed && StringUtils.equals(type.kind, "string")) {
                    throw this.error("XDR string uses a variable bound");
                }
                type.length = length;
                type.fixed = fixed;
            } else {
                XdrIdlType array = new XdrIdlType("array", "array");
                array.element = type;
                array.fixed = fixed;
                array.length = length;
                type = array;
            }
        } else if (StringUtils.equals(type.kind, "string") || StringUtils.equals(type.kind, "opaque")) {
            throw this.error("String and opaque declarations require an array bound");
        }
        return new XdrIdlMember(name, type);
    }

    private XdrIdlType type() throws IOException {
        String name = this.identifier();
        if (StringUtils.equals(name, "unsigned")) {
            if (this.take("hyper")) {
                return new XdrIdlType("unsigned_hyper", "unsigned_hyper");
            }
            this.take("int");
            return new XdrIdlType("unsigned_int", "unsigned_int");
        }
        Set<String> scalar = Set.of("int", "hyper", "bool", "float", "double", "quadruple", "void", "string", "opaque");
        return new XdrIdlType(scalar.contains(name) ? name : "reference", name);
    }

    private XdrIdlType declare(String kind, String name) {
        XdrIdlType type = new XdrIdlType(kind, name);
        if (this.document.types.putIfAbsent(name, type) != null) {
            throw this.error("Duplicate type: " + name);
        }
        return type;
    }

    private long number() throws IOException {
        String value = this.token;
        Long constant = this.document.constants.get(value);
        try {
            long number;
            if (constant != null) {
                number = constant;
            } else if (value != null && value.matches("[+-]?0[xX][0-9a-fA-F]+")) {
                number = Long.decode(value);
            } else {
                number = Long.parseLong(value);
            }
            this.next();
            return number;
        } catch (NumberFormatException failure) {
            throw this.error("Expected an integer or declared constant: " + value);
        }
    }

    private long unsigned() throws IOException {
        long value = this.number();
        if (value < 0 || value > 0xffffffffL) {
            throw this.error("Number exceeds unsigned 32-bit range");
        }
        return value;
    }

    private String identifier() throws IOException {
        if (this.token == null || !this.token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw this.error("Expected identifier: " + this.token);
        }
        String name = this.token;
        this.next();
        return name;
    }

    private boolean is(String value) {
        return StringUtils.equals(this.token, value);
    }

    private boolean take(String value) throws IOException {
        if (!this.is(value)) {
            return false;
        }
        this.next();
        return true;
    }

    private void expect(String value) throws IOException {
        if (!this.take(value)) {
            throw this.error("Expected '" + value + "', found " + this.token);
        }
    }

    private void next() throws IOException {
        int token = this.tokens.nextToken();
        if (token == StreamTokenizer.TT_EOF) {
            this.token = null;
        } else if (token == StreamTokenizer.TT_WORD) {
            this.token = this.tokens.sval;
        } else {
            this.token = Character.toString((char) token);
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(this.document.source + ":" + this.tokens.lineno() + ": " + message);
    }
}
