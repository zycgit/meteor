/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;
import org.w3c.dom.Element;

/** 为单元测试提供资源模型的包内只读访问。 */
public final class SoapWsdlResourceAccess {
    private SoapWsdlResourceAccess() {
    }

    public static String soapVersion(SoapWsdlResource resource) {
        return resource.definition().version().displayName();
    }

    public static String targetNamespace(SoapWsdlResource resource) {
        return resource.definition().targetNamespace();
    }

    public static int operationCount(SoapWsdlResource resource) {
        return resource.definition().operations().size();
    }

    public static String soapAction(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).soapAction();
    }

    public static boolean actionRequired(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).actionRequired();
    }

    public static String inputElement(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).inputElement().toString();
    }

    public static QName inputWrapper(SoapWsdlResource resource, String operationName, String parameterStyle) {
        return resource.parameterStyle(parameterStyle).definition().operation(operationName).inputBody().wrapper();
    }

    public static String[] parameterElements(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).parameterElements().stream() //
                .map(Object::toString) //
                .toArray(String[]::new);
    }

    public static int parameterMinOccurs(SoapWsdlResource resource, String operationName, int parameterIndex) {
        return resource.definition().operation(operationName).inputBody().parts().get(parameterIndex).minOccurs();
    }

    public static int parameterMaxOccurs(SoapWsdlResource resource, String operationName, int parameterIndex) {
        return resource.definition().operation(operationName).inputBody().parts().get(parameterIndex).maxOccurs();
    }

    public static int choiceCount(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).inputBody().choices().size();
    }

    public static int choiceMinOccurs(SoapWsdlResource resource, String operationName, int choiceIndex) {
        return resource.definition().operation(operationName).inputBody().choices().get(choiceIndex).minOccurs();
    }

    public static List<Integer> choicePartIndexes(SoapWsdlResource resource, String operationName, int choiceIndex) {
        return resource.definition().operation(operationName).inputBody().choices().get(choiceIndex).partIndexes();
    }

    public static String resultElement(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).resultElement().toString();
    }

    public static boolean oneWay(SoapWsdlResource resource, String operationName) {
        return resource.definition().operation(operationName).oneWay();
    }

    public static Set<String> resourceNames(SoapWsdlResource resource) {
        return resource.resourceNames();
    }

    public static byte[] resource(SoapWsdlResource resource, String name) {
        return resource.resource(name);
    }

    public static byte[] renderWsdl(SoapWsdlResource resource, URI endpoint) throws IOException {
        return resource.renderWsdl(endpoint);
    }

    public static void validateInput(SoapWsdlResource resource, String operationName, //
            Element body) throws IOException {
        SoapBody binding = resource.definition().operation(operationName).inputBody();
        resource.validateBody(body, binding);
    }

    public static void validateInputHeader(SoapWsdlResource resource, String operationName, int headerIndex, //
            Element payload) throws IOException {
        SoapOperation operation = resource.definition().operation(operationName);
        SoapPart part = operation.inputHeaders().get(headerIndex).part();
        resource.validatePart(payload, part);
    }
}
