/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.meteor.test.nxn;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 原始 JUnit 报告的失败原因保留，不依赖真实对端或测试调度器。 */
public class NxnJUnitReportTest {
    @TempDir
    public Path temporary;

    @Test
    public void failureWithoutBodyPreservesTheMessageAttribute() throws Exception {
        // 第三方写入器可以只通过 failure 属性表达异常原因。
        Path xml = this.result("<failure message=\"RPC timed out\" type=\"TimeoutException\"/>");

        // 没有正文时，摘要仍可明确指出失败原因。
        Map<String, Object> testcase = NxnJUnitReport.readCases(List.of(xml)).get(0);
        assertEquals("RPC timed out", testcase.get("failure"));
    }

    @Test
    public void failureWithoutMessagePreservesTheExceptionType() throws Exception {
        // 最小 XML 可能只保留异常类型，没有 message 和堆栈正文。
        Path xml = this.result("<failure type=\"ConnectionException\"/>");
        Map<String, Object> testcase = NxnJUnitReport.readCases(List.of(xml)).get(0);
        assertEquals("ConnectionException", testcase.get("failure"));
    }

    @Test
    public void failureBodyRemainsComplete() throws Exception {
        // 原始正文比 message 属性详细，完整堆栈应优先原样保留。
        String body = "java.lang.AssertionError: response\n    at RPC.call(RPC.java:12)\n";
        Path xml = this.result("<failure message=\"short message\">" + body + "</failure>");

        // 不因摘要格式而删除换行或截断内容。
        Map<String, Object> testcase = NxnJUnitReport.readCases(List.of(xml)).get(0);
        assertEquals(body, testcase.get("failure"));
    }

    @Test
    public void multipleFailuresRemainReadableInOneCase() throws Exception {
        // 同一用例的执行和清理可以分别报告失败，但仍只算一个失败用例。
        Path xml = this.result("<failure>call failed</failure><error>cleanup failed</error>");

        // 两份原因均保留，并通过换行区分。
        List<Map<String, Object>> cases = NxnJUnitReport.readCases(List.of(xml));
        assertEquals(1, cases.size());
        assertEquals("failed", cases.get(0).get("status"));
        assertEquals("call failed\ncleanup failed", cases.get(0).get("failure"));
    }

    private Path result(String outcome) throws Exception {
        Path xml = this.temporary.resolve("TEST-rpc.Calls.xml");
        String content = "<testsuite name=\"rpc.Calls\" tests=\"1\">" //
                + "<testcase classname=\"rpc.Calls\" name=\"invoke\">" //
                + outcome + "</testcase></testsuite>";
        Files.writeString(xml, content, StandardCharsets.UTF_8);
        return xml;
    }
}
