/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.realmeteor.environment.execution;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.UniqueId;
import java.util.ArrayList;
import java.util.List;

/** 在 Jupiter 展开参数后选择当前工作组的向量，并保留快速失败后的未执行身份。 */
public final class JUnitJobCondition implements ExecutionCondition {
    static final String INTERNAL_REASON = "NXN internal: ";
    // Jupiter 的 SEPARATE_THREAD 超时线程继承所属工作组，独立 JVM 不跨组共享此上下文。
    private static final InheritableThreadLocal<JUnitJobReport> CURRENT = new InheritableThreadLocal<>();

    static JUnitJobReport current() {
        return CURRENT.get();
    }

    static void current(JUnitJobReport report) {
        if (report == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(report);
        }
    }

    /** 声明当前标准用例的准备、调用或清理阶段；普通 Gradle 执行没有 NXN 报告时不做处理。 */
    public static void phase(String caseId, String phase) {
        JUnitJobReport report = CURRENT.get();
        if (report != null) {
            report.phase(caseId, phase);
        }
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        JUnitJobReport report = CURRENT.get();
        if (report == null || context.getTestMethod().isEmpty()) {
            return ConditionEvaluationResult.enabled("No NXN method selection");
        }

        // 参数实例由 Jupiter 创建；筛选沿用方法名与未经改写的向量显示名称。
        String name = context.getRequiredTestMethod().getName();
        List<String> parameterNames = new ArrayList<>();
        ExtensionContext parent = context.getParent().orElse(null);
        while (parent != null) {
            String segment = UniqueId.parse(parent.getUniqueId()).getLastSegment().getType();
            if (StringUtils.equals("class-template-invocation", segment)) {
                name += "[" + parent.getDisplayName() + "]";
                parameterNames.add(parent.getDisplayName());
            }
            parent = parent.getParent().orElse(null);
        }
        String segment = UniqueId.parse(context.getUniqueId()).getLastSegment().getType();
        if (StringUtils.equals("test-template-invocation", segment)) {
            name += "[" + context.getDisplayName() + "]";
            parameterNames.add(context.getDisplayName());
        }
        if (!report.selected(context.getRequiredTestClass().getName(), name, parameterNames)) {
            return ConditionEvaluationResult.disabled(INTERNAL_REASON + "not assigned to this group");
        }
        if (report.failureStopped()) {
            return ConditionEvaluationResult.disabled(INTERNAL_REASON + "stopped after testcase failure");
        }
        return ConditionEvaluationResult.enabled("Assigned NXN testcase");
    }
}
