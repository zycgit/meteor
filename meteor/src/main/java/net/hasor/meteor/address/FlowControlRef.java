/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import net.hasor.meteor.address.route.random.RandomFlowControl;
import net.hasor.meteor.address.route.speed.SpeedFlowControl;
import net.hasor.meteor.address.route.unit.UnitFlowControl;

/** 一份编译完成的流控配置，随地址桶整体替换。 */
public class FlowControlRef {
    public String            flowControlScript;
    public UnitFlowControl   unitFlowControl;
    public RandomFlowControl randomFlowControl;
    public SpeedFlowControl  speedFlowControl;

    private FlowControlRef() {
    }

    public static FlowControlRef defaultRef() {
        FlowControlRef flowControlRef = new FlowControlRef();
        flowControlRef.randomFlowControl = new RandomFlowControl();
        flowControlRef.speedFlowControl = SpeedFlowControl.defaultControl();
        return flowControlRef;
    }
}