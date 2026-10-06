/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
/**
 *
 * @version : 2015年10月20日
 * @author 赵永春 (zyc@hasor.net)
 */
public enum RouteTypeEnum {
    ServiceLevel(1, "服务级"),
    MethodLevel(2, "方法级"),
    ArgsLevel(3, "参数级"),
    ;
    private final int    type;
    private final String desc;

    RouteTypeEnum(int type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    @Override
    public String toString() {
        return "Enum[type = " + this.type + " , desc = " + this.desc + "]";
    }

    static boolean updateScript(RouteTypeEnum routeType, String script, RuleRef ref) {
        /*  */
        if (RouteTypeEnum.ServiceLevel.equals(routeType)) {
            return ref.getServiceLevel().update(script);
        } else if (RouteTypeEnum.MethodLevel.equals(routeType)) {
            return ref.getMethodLevel().update(script);
        } else if (RouteTypeEnum.ArgsLevel.equals(routeType)) {
            return ref.getArgsLevel().update(script);
        }
        return false;
    }
}