/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route;
/**
 * 将参数映射为一个Key.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2015年4月16日
 */
public class DefaultArgsKey implements ArgsKey {
    public String eval(final String serviceID, final String methodName, final Object[] args) {
        if (args == null) {
            return "null";
        }

        StringBuilder strBuilder = new StringBuilder();
        for (Object obj : args) {
            if (obj == null) {
                strBuilder.append("null");
            } else {
                String value = obj.toString();
                // Keep ordinary legacy keys; escape separators and the reserved null token.
                if ("null".equals(value)) {
                    strBuilder.append("\\null");
                } else {
                    strBuilder.append(value.replace("\\", "\\\\").replace("-", "\\-"));
                }
            }
            strBuilder.append("-");
        }
        return strBuilder.toString();
    }
}