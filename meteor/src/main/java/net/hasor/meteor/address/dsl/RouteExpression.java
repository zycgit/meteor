/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import ognl.MemberAccess;
import ognl.Node;
import ognl.Ognl;
import ognl.OgnlContext;

/** 表达式只在更新时解析；执行不共享 OGNL 上下文。 */
final class RouteExpression {
    private static final Set<String>  NODES  = Set.of(//
            "ASTConst", "ASTProperty", "ASTChain", "ASTMethod", "ASTList",//
            "ASTAdd", "ASTSubtract", "ASTMultiply", "ASTDivide", "ASTRemainder", "ASTNegate",//
            "ASTAnd", "ASTOr", "ASTNot", "ASTEq", "ASTNotEq", "ASTLess", "ASTLessEq",//
            "ASTGreater", "ASTGreaterEq", "ASTTest", "ASTIn", "ASTNotIn");
    private static final MemberAccess ACCESS = new ReadAccess();
    private final        String       source;
    private final        Object       expression;

    public RouteExpression(String source) {
        this.source = source;
        try {
            this.expression = Ognl.parseExpression(source);
            validate((Node) this.expression);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid route expression: " + source, failure);
        }
    }

    public Object evaluate(Map<String, Object> variables) {
        try {
            OgnlContext context = new OgnlContext(null, null, ACCESS);
            context.setRoot(variables);
            return Ognl.getValue(this.expression, context, variables);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot evaluate route expression: " + this.source, failure);
        }
    }

    public boolean test(Map<String, Object> variables) {
        Object value = this.evaluate(variables);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("Route condition must return boolean: " + this.source);
        }
        return (Boolean) value;
    }

    private static void validate(Node node) {
        if (!NODES.contains(node.getClass().getSimpleName())) {
            throw new IllegalArgumentException("Unsupported route expression: " + node);
        }
        for (int index = 0; index < node.jjtGetNumChildren(); index++) {
            validate(node.jjtGetChild(index));
        }
    }

    private static final class ReadAccess implements MemberAccess {
        @Override
        public Object setup(OgnlContext context, Object target, Member member, String propertyName) {
            return null;
        }

        @Override
        public void restore(OgnlContext context, Object target, Member member, String propertyName, Object state) {
        }

        @Override
        public boolean isAccessible(OgnlContext context, Object target, Member member, String propertyName) {
            if (!(member instanceof Method method) || !Modifier.isPublic(member.getModifiers())) {
                return false;
            }
            if (Modifier.isStatic(member.getModifiers()) || target instanceof Class<?>) {
                return false;
            }
            String name = method.getName();
            if (StringUtils.equals(name, "getClass")) {
                return false;
            }
            if (target instanceof RouteUtils) {
                return method.getDeclaringClass() == RouteUtils.class;
            }
            if (target instanceof Collection<?> || target instanceof Map<?, ?>) {
                return Set.of("size", "isEmpty", "contains", "containsKey", "get").contains(name);
            }
            // 普通参数对象和地址只允许读取 Bean 属性。
            boolean getter = name.startsWith("get") || name.startsWith("is");
            return getter && method.getParameterCount() == 0;
        }
    }
}