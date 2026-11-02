/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.*;
import javax.xml.namespace.QName;
import net.hasor.cobble.StringUtils;

/** 已解析的 SOAP 服务定义。 */
public final class SoapDefinition {
    private final SoapVersion                version;
    private final String                     targetNamespace;
    private final Map<String, SoapOperation> methods;

    SoapDefinition(SoapVersion version, String targetNamespace, Collection<SoapOperation> operations) {
        Map<String, SoapOperation> methods = new LinkedHashMap<>();
        Map<List<QName>, Map<String, SoapOperation>> signatures = new LinkedHashMap<>();
        for (SoapOperation operation : operations) {
            SoapOperation oldMethod = methods.putIfAbsent(operation.name(), operation);
            if (oldMethod != null) {
                throw new IllegalArgumentException("Duplicate SOAP operation name: " + operation.name());
            }
            SoapBody body = operation.inputBody();
            List<QName> signature = body.wrapper() == null ? body.elements() : List.of(body.wrapper());
            Map<String, SoapOperation> actions = signatures.computeIfAbsent(signature, //
                    ignored -> new LinkedHashMap<>());
            if (actions.putIfAbsent(operation.soapAction(), operation) != null) {
                throw new IllegalArgumentException("Duplicate SOAP Body and action signature: " + signature);
            }
        }

        this.version = version;
        this.targetNamespace = targetNamespace;
        this.methods = Collections.unmodifiableMap(methods);
    }

    SoapVersion version() {
        return this.version;
    }

    String targetNamespace() {
        return this.targetNamespace;
    }

    public Collection<SoapOperation> operations() {
        return this.methods.values();
    }

    public SoapOperation operation(String methodName) {
        return this.methods.get(methodName);
    }

    public SoapOperation resolve(List<QName> bodyElements, String action) {
        SoapOperation selected = null;
        for (SoapOperation operation : this.methods.values()) {
            if (!operation.inputBody().matches(bodyElements)) {
                continue;
            }
            if (action != null && !StringUtils.equals(action, operation.soapAction())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException("Ambiguous SOAP operation for Body and action: " + bodyElements);
            }
            selected = operation;
        }
        return selected;
    }
}
