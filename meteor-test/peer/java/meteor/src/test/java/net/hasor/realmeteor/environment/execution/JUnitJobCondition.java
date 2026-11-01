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
    private static final ThreadLocal<JUnitJobReport> CURRENT = new ThreadLocal<>();

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
