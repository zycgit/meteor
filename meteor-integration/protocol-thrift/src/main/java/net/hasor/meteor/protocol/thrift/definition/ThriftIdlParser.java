/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.definition;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.StringUtils;
import org.apache.thrift.TFieldRequirementType;

/** 读取 Thrift IDL 的声明和导入图；语法错误附带资源地址和行号。 */
public final class ThriftIdlParser {
    private final ThriftIdlDocument document;
    private final StreamTokenizer   tokens;
    private       String            token;
    private       boolean           quoted;

    private ThriftIdlParser(ThriftIdlDocument document) throws IOException {
        this.document = document;
        this.tokens = new StreamTokenizer(new StringReader(document.text));
        this.tokens.resetSyntax();
        this.tokens.whitespaceChars(0, 32);
        this.tokens.wordChars('a', 'z');
        this.tokens.wordChars('A', 'Z');
        this.tokens.wordChars('0', '9');
        this.tokens.wordChars('_', '_');
        this.tokens.wordChars('.', '.');
        this.tokens.wordChars('-', '-');
        this.tokens.wordChars('+', '+');
        this.tokens.quoteChar('"');
        this.tokens.quoteChar('\'');
        this.tokens.commentChar('#');
        this.tokens.slashSlashComments(true);
        this.tokens.slashStarComments(true);
        this.next();
    }

    public static ThriftIdlDocument load(URL source) throws IOException {
        return load(source, new LinkedHashMap<>());
    }

    private static ThriftIdlDocument load(URL source, Map<String, ThriftIdlDocument> documents) throws IOException {
        String identity = source.toExternalForm();
        ThriftIdlDocument existing = documents.get(identity);
        if (existing != null) {
            return existing;
        }
        String text;
        try (InputStream input = source.openStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        ThriftIdlDocument document = new ThriftIdlDocument(source, text);
        documents.put(identity, document);
        new ThriftIdlParser(document).parse(documents);
        return document;
    }

    private void parse(Map<String, ThriftIdlDocument> documents) throws IOException {
        while (this.token != null) {
            if (this.take("include")) {
                String path = this.string();
                String name = path.substring(path.lastIndexOf('/') + 1);
                int extension = name.lastIndexOf('.');
                if (extension >= 0) {
                    name = name.substring(0, extension);
                }
                this.document.includePaths.put(name, path);
                ThriftIdlDocument imported = load(new URL(this.document.source, path), documents);
                if (this.document.includes.putIfAbsent(name, imported) != null) {
                    throw this.error("Duplicate include: " + name);
                }
            } else if (this.take("namespace")) {
                String language = this.token;
                this.next();
                this.document.namespaces.put(language, this.identifier());
                this.annotations(new LinkedHashMap<>());
            } else if (this.take("cpp_include")) {
                this.string();
            } else if (this.take("const")) {
                ThriftIdlType type = this.type();
                String name = this.identifier();
                this.document.constantTypes.put(name, type);
                this.expect("=");
                if (this.document.constants.putIfAbsent(name, this.value()) != null) {
                    throw this.error("Duplicate constant: " + name);
                }
            } else if (this.take("typedef")) {
                ThriftIdlType target = this.type();
                ThriftIdlType alias = this.declare("typedef", this.identifier());
                alias.element = target;
                this.annotations(alias.annotations);
            } else if (this.take("enum")) {
                this.enumeration();
            } else if (this.is("struct") || this.is("union") || this.is("exception")) {
                String kind = this.token;
                this.next();
                ThriftIdlType type = this.declare(kind, this.identifier());
                this.expect("{");
                this.fields(type.fields, "}");
                this.annotations(type.annotations);
            } else if (this.take("service")) {
                this.service();
            } else {
                throw this.error("Unsupported declaration: " + this.token);
            }
            this.separator();
        }
    }

    private void enumeration() throws IOException {
        ThriftIdlType type = this.declare("enum", this.identifier());
        this.expect("{");
        long next = 0;
        while (!this.take("}")) {
            String name = this.identifier();
            long number = this.take("=") ? this.integer() : next;
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw this.error("Enum number exceeds i32: " + name);
            }
            if (type.enumeration.putIfAbsent(name, (int) number) != null) {
                throw this.error("Duplicate enum name: " + name);
            }
            next = number + 1;
            Map<String, String> annotations = new LinkedHashMap<>();
            this.annotations(annotations);
            type.enumerationAnnotations.put(name, annotations);
            this.separator();
        }
        this.annotations(type.annotations);
    }

    private void service() throws IOException {
        ThriftIdlType service = this.declare("service", this.identifier());
        if (this.take("extends")) {
            service.element = this.type();
        }
        this.expect("{");
        while (!this.take("}")) {
            boolean oneway = this.take("oneway");
            ThriftIdlType result = this.type();
            String name = this.identifier();
            ThriftIdlMethod method = new ThriftIdlMethod(name, result, oneway);
            this.expect("(");
            this.fields(method.parameters, ")");
            if (this.take("throws")) {
                this.expect("(");
                this.fields(method.exceptions, ")");
            }
            this.annotations(method.annotations);
            if (service.methods.putIfAbsent(name, method) != null) {
                throw this.error("Duplicate method: " + name);
            }
            this.separator();
        }
        this.annotations(service.annotations);
    }

    private void fields(List<ThriftIdlField> fields, String end) throws IOException {
        Set<Short> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        int implicit = -1;
        while (!this.take(end)) {
            int id = implicit;
            if (this.token != null && this.token.matches("[+-]?[0-9]+")) {
                long number = this.integer();
                if (number < Short.MIN_VALUE || number > Short.MAX_VALUE) {
                    throw this.error("Field id exceeds i16");
                }
                id = (int) number;
                this.expect(":");
            } else {
                implicit--;
            }
            byte requirement = TFieldRequirementType.DEFAULT;
            if (this.take("required")) {
                requirement = TFieldRequirementType.REQUIRED;
            } else if (this.take("optional")) {
                requirement = TFieldRequirementType.OPTIONAL;
            }
            ThriftIdlType type = this.type();
            String name = this.identifier();
            Object value = this.take("=") ? this.value() : null;
            if (!ids.add((short) id) || !names.add(name)) {
                throw this.error("Duplicate field: " + name);
            }
            ThriftIdlField field = new ThriftIdlField((short) id, name, type, requirement, value);
            this.annotations(field.annotations);
            fields.add(field);
            this.separator();
        }
    }

    private ThriftIdlType type() throws IOException {
        String name = this.identifier();
        boolean container = StringUtils.equals(name, "list") || StringUtils.equals(name, "set");
        boolean map = StringUtils.equals(name, "map");
        Set<String> primitives = Set.of("bool", "byte", "i8", "i16", "i32", "i64", //
                "double", "string", "binary", "uuid", "void");
        String kind = primitives.contains(name) || container || map ? name : "reference";
        ThriftIdlType type = new ThriftIdlType(kind, name);
        type.document = this.document;
        if (container || map) {
            this.expect("<");
            ThriftIdlType first = this.type();
            if (map) {
                type.key = first;
                this.expect(",");
                type.element = this.type();
            } else {
                type.element = first;
            }
            this.expect(">");
        }
        this.annotations(type.annotations);
        return type;
    }

    private ThriftIdlValue value() throws IOException {
        if (this.quoted) {
            return new ThriftIdlValue(this.document, "literal", this.string());
        }
        if (this.take("[")) {
            List<ThriftIdlValue> values = new ArrayList<>();
            while (!this.take("]")) {
                values.add(this.value());
                this.separator();
            }
            return new ThriftIdlValue(this.document, "list", values);
        }
        if (this.take("{")) {
            Map<ThriftIdlValue, ThriftIdlValue> values = new LinkedHashMap<>();
            while (!this.take("}")) {
                ThriftIdlValue key = this.value();
                this.expect(":");
                values.put(key, this.value());
                this.separator();
            }
            return new ThriftIdlValue(this.document, "map", values);
        }
        String value = this.token;
        if (value == null) {
            throw this.error("Expected constant value");
        }
        this.next();
        if (StringUtils.equals(value, "true") || StringUtils.equals(value, "false")) {
            return new ThriftIdlValue(this.document, "literal", Boolean.parseBoolean(value));
        }
        try {
            if (value.matches("[+-]?0[xX][0-9a-fA-F]+")) {
                return new ThriftIdlValue(this.document, "literal", Long.decode(value));
            }
            if (value.matches("[+-]?[0-9]+")) {
                return new ThriftIdlValue(this.document, "literal", Long.parseLong(value));
            }
            if (value.matches("[+-]?[0-9.]+([eE][+-]?[0-9]+)?")) {
                return new ThriftIdlValue(this.document, "literal", new BigDecimal(value));
            }
        } catch (NumberFormatException failure) {
            throw this.error("Invalid numeric constant: " + value);
        }
        return new ThriftIdlValue(this.document, "reference", value);
    }

    private ThriftIdlType declare(String kind, String name) {
        ThriftIdlType type = new ThriftIdlType(kind, name);
        type.document = this.document;
        if (this.document.types.putIfAbsent(name, type) != null) {
            throw this.error("Duplicate type: " + name);
        }
        return type;
    }

    private void annotations(Map<String, String> target) throws IOException {
        if (!this.take("(")) {
            return;
        }
        while (!this.take(")")) {
            String name = this.identifier();
            String value = "1";
            if (this.take("=")) {
                value = this.token;
                this.next();
            }
            target.put(name, value);
            this.separator();
        }
    }

    private long integer() throws IOException {
        try {
            long number = Long.decode(this.token);
            this.next();
            return number;
        } catch (NumberFormatException failure) {
            throw this.error("Expected integer: " + this.token);
        }
    }

    private String identifier() throws IOException {
        if (this.quoted || this.token == null || !this.token.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw this.error("Expected identifier: " + this.token);
        }
        String name = this.token;
        this.next();
        return name;
    }

    private String string() throws IOException {
        if (!this.quoted) {
            throw this.error("Expected quoted string");
        }
        String value = this.token;
        this.next();
        return value;
    }

    private void separator() throws IOException {
        if (!this.take(",")) {
            this.take(";");
        }
    }

    private boolean is(String value) {
        return !this.quoted && StringUtils.equals(this.token, value);
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
        this.quoted = token == '\'' || token == '"';
        if (token == StreamTokenizer.TT_EOF) {
            this.token = null;
        } else if (token == StreamTokenizer.TT_WORD || this.quoted) {
            this.token = this.tokens.sval;
        } else {
            this.token = Character.toString((char) token);
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(this.document.source + ":" + this.tokens.lineno() + ": " + message);
    }
}
