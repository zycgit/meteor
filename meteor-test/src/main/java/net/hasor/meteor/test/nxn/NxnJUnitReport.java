/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.meteor.test.nxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads immutable JUnit evidence and merges split parameterized suites. */
final class NxnJUnitReport {
    private NxnJUnitReport() {
    }

    static List<Map<String, Object>> readCases(List<Path> files) throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Path path : files) {
            // 核对套件统计与真实 testcase，保留参数化名称作为执行身份。
            Element suite = readSuite(path);
            List<Element> children = children(suite, "testcase");
            checkCount(suite, "tests", children.size(), path);
            checkCount(suite, "failures", count(children, "failure"), path);
            checkCount(suite, "errors", count(children, "error"), path);
            checkCount(suite, "skipped", count(children, "skipped"), path);
            for (Element child : children) {
                String className = child.getAttribute("classname");
                String scenario = child.getAttribute("name");
                if (className.isEmpty() || scenario.isEmpty()) {
                    throw new IllegalArgumentException("Testcase has no identity: " + path);
                }

                List<Element> failures = children(child, "failure");
                failures.addAll(children(child, "error"));
                String status = "passed";
                if (!failures.isEmpty()) {
                    status = "failed";
                } else if (!children(child, "skipped").isEmpty()) {
                    status = "skipped";
                }

                String seconds = child.hasAttribute("time") ? child.getAttribute("time") : "0";
                new BigDecimal(seconds);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("caseId", className + "#" + scenario);
                entry.put("className", className);
                entry.put("scenario", scenario);
                entry.put("seconds", seconds);
                entry.put("status", status);
                if (child.hasAttribute("phase")) {
                    entry.put("phase", child.getAttribute("phase"));
                }

                Map<String, String> logs = new LinkedHashMap<>();
                for (String name : List.of("stdout", "stderr", "suiteStdout", "suiteStderr")) {
                    if (child.hasAttribute(name)) {
                        logs.put(name, child.getAttribute(name));
                    }
                }

                if (!logs.isEmpty()) {
                    entry.put("logs", logs);
                }

                if (!failures.isEmpty()) {
                    StringBuilder message = new StringBuilder();
                    for (Element failure : failures) {
                        if (!message.isEmpty()) {
                            message.append('\n');
                        }
                        String trace = failure.getTextContent();
                        if (trace.isEmpty()) {
                            // 部分 JUnit 写入器只输出属性，仍需保留真实失败原因。
                            trace = failure.getAttribute("message");
                            if (trace.isEmpty()) {
                                trace = failure.getAttribute("type");
                            }
                        }
                        message.append(trace);
                    }
                    entry.put("failure", message.toString());
                }
                cases.add(entry);
            }
        }
        return cases;
    }

    static void mergeSuites(Map<String, Path> sources, Path destination) throws Exception {
        if (sources.size() == 1) {
            Files.copy(sources.values().iterator().next(), destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // 分组拆开的同一套件保留各组属性、用例和输出，源文件始终不变。
        Document mergedDocument = factory().newDocumentBuilder().newDocument();
        Element merged = null;
        Element properties = mergedDocument.createElement("properties");
        BigDecimal seconds = BigDecimal.ZERO;
        List<String> timestamps = new ArrayList<>();
        Map<String, StringBuilder> output = new LinkedHashMap<>();
        output.put("system-out", new StringBuilder());
        output.put("system-err", new StringBuilder());
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            Element suite = readSuite(source.getValue());
            if (merged == null) {
                merged = (Element) mergedDocument.importNode(suite, false);
                mergedDocument.appendChild(merged);
                merged.appendChild(properties);
            } else if (!StringUtils.equals(merged.getAttribute("name"), suite.getAttribute("name"))) {
                String message = "Cannot merge different JUnit suites into " + destination.getFileName();
                throw new IllegalArgumentException(message);
            }

            Element property = mergedDocument.createElement("property");
            property.setAttribute("name", "meteor.group." + source.getKey() + ".source");
            property.setAttribute("value", source.getValue().toString());
            properties.appendChild(property);
            for (Element sourceProperties : children(suite, "properties")) {
                NodeList nodes = sourceProperties.getChildNodes();
                for (int index = 0; index < nodes.getLength(); index++) {
                    properties.appendChild(mergedDocument.importNode(nodes.item(index), true));
                }
            }

            for (Element testCase : children(suite, "testcase")) {
                merged.appendChild(mergedDocument.importNode(testCase, true));
            }

            for (Map.Entry<String, StringBuilder> stream : output.entrySet()) {
                for (Element node : children(suite, stream.getKey())) {
                    if (!stream.getValue().isEmpty()) {
                        stream.getValue().append('\n');
                    }
                    stream.getValue().append('[').append(source.getKey()).append("]\n");
                    stream.getValue().append(node.getTextContent());
                }
            }

            seconds = seconds.add(new BigDecimal(suite.hasAttribute("time") ? suite.getAttribute("time") : "0"));
            if (suite.hasAttribute("timestamp") && !suite.getAttribute("timestamp").isEmpty()) {
                timestamps.add(suite.getAttribute("timestamp"));
            }
        }

        if (merged == null) {
            throw new IllegalArgumentException("No JUnit suites to merge");
        }

        // 合并后重新计算统计，输出仍可追踪到原始分组。
        List<Element> cases = children(merged, "testcase");
        merged.setAttribute("tests", Integer.toString(cases.size()));
        merged.setAttribute("failures", Integer.toString(count(cases, "failure")));
        merged.setAttribute("errors", Integer.toString(count(cases, "error")));
        merged.setAttribute("skipped", Integer.toString(count(cases, "skipped")));
        merged.setAttribute("time", seconds.toPlainString());
        if (!timestamps.isEmpty()) {
            merged.setAttribute("timestamp", timestamps.stream().min(Comparator.naturalOrder()).orElseThrow());
        }

        for (Map.Entry<String, StringBuilder> stream : output.entrySet()) {
            if (!stream.getValue().isEmpty()) {
                Element node = mergedDocument.createElement(stream.getKey());
                node.setTextContent(stream.getValue().toString());
                merged.appendChild(node);
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(mergedDocument), new StreamResult(destination.toFile()));
    }

    private static Element readSuite(Path path) throws Exception {
        Element suite = factory().newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        if (!StringUtils.equals("testsuite", suite.getTagName())) {
            throw new IllegalArgumentException("Expected a JUnit testsuite: " + path);
        }
        return suite;
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element && StringUtils.equals(tag, ((Element) node).getTagName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static int count(List<Element> cases, String tag) {
        int count = 0;
        for (Element testCase : cases) {
            if (!children(testCase, tag).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static void checkCount(Element suite, String name, int expected, Path path) {
        if (suite.hasAttribute(name) && Integer.parseInt(suite.getAttribute(name)) != expected) {
            throw new IllegalArgumentException("JUnit suite count disagrees with testcase elements: " + path);
        }
    }
}
