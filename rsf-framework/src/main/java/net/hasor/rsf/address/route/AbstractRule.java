/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.route;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.cobble.setting.Settings;

/**
 * 路由规则，配置模版实例：
 * <pre>
 * &lt;flowControl enable="true|false" type="Room"&gt;
 *   ...
 * &lt;/flowControl&gt;
 * </pre>
 * @version : 2015年3月29日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractRule implements Rule {
    protected Logger  logger = LoggerFactory.getLogger(getClass());
    private   String  routeID;
    private   String  routeBody;
    private   boolean enable;

    /**路由规则ID*/
    public String routeID() {
        return this.routeID;
    }

    /**路由规则原文*/
    public String rawRoute() {
        return this.routeBody;
    }

    /**规则是否启用*/
    public boolean enable() {
        return this.enable;
    }

    /**设置规则是否启用*/
    protected void enable(boolean enable) {
        this.enable = enable;
    }

    /**设置规则ID*/
    protected void setRouteID(String routeID) {
        this.routeID = routeID;
    }

    /**设置规则内容*/
    protected void setRouteBody(String routeBody) {
        this.routeBody = routeBody;
    }

    @Override
    public String toString() {
        return "AbstractRule{" + "routeID=" + this.routeID + "', enable=" + this.enable + '}';
    }

    /**应用配置初始化规则器*/
    public abstract void parseControl(Settings settings);
}
