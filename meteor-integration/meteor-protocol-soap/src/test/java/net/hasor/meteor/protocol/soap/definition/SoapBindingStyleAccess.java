/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.List;
import javax.xml.namespace.QName;

/** 为 binding 单元测试提供操作和 Body 模型的包内只读访问。 */
public final class SoapBindingStyleAccess {
    private SoapBindingStyleAccess() {
    }

    public static String style(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).style();
    }

    public static QName inputWrapper(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().wrapper();
    }

    public static QName outputWrapper(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().wrapper();
    }

    public static String inputUse(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().use();
    }

    public static String outputUse(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().use();
    }

    public static String inputEncodingStyle(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().encodingStyle();
    }

    public static String outputEncodingStyle(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().encodingStyle();
    }

    public static String[] inputPartNames(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().parts().stream() //
                .map(SoapPart::name) //
                .toArray(String[]::new);
    }

    public static String[] outputPartNames(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().parts().stream() //
                .map(SoapPart::name) //
                .toArray(String[]::new);
    }

    public static List<QName> inputElements(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().elements();
    }

    public static List<QName> outputElements(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().elements();
    }

    public static List<QName> inputTypes(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).inputBody().parts().stream().map(SoapPart::type).toList();
    }

    public static List<QName> outputTypes(SoapWsdlResource resource, String operation) {
        return resource.definition().operation(operation).outputBody().parts().stream().map(SoapPart::type).toList();
    }

    public static int inputMinOccurs(SoapWsdlResource resource, String operation, int partIndex) {
        return resource.definition().operation(operation).inputBody().parts().get(partIndex).minOccurs();
    }

    public static int inputMaxOccurs(SoapWsdlResource resource, String operation, int partIndex) {
        return resource.definition().operation(operation).inputBody().parts().get(partIndex).maxOccurs();
    }

    public static String resolve(SoapWsdlResource resource, List<QName> bodyElements, String action) {
        SoapOperation operation = resource.definition().resolve(bodyElements, action);
        return operation == null ? null : operation.name();
    }
}
