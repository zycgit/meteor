/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2015年12月3日
 */
public class RuleRef {
    public RuleRef(RuleRef scriptResourcesRef) {
        if (scriptResourcesRef != null) {
            this.serviceLevel = new InnerRuleEngine(scriptResourcesRef.serviceLevel);
            this.methodLevel = new InnerRuleEngine(scriptResourcesRef.methodLevel);
            this.argsLevel = new InnerRuleEngine(scriptResourcesRef.argsLevel);
        } else {
            this.serviceLevel = new InnerRuleEngine();
            this.methodLevel = new InnerRuleEngine();
            this.argsLevel = new InnerRuleEngine();
        }
    }

    private InnerRuleEngine serviceLevel; //服务级
    private InnerRuleEngine methodLevel; //方法级
    private InnerRuleEngine argsLevel; //参数级

    public InnerRuleEngine getServiceLevel() {
        return this.serviceLevel;
    }

    public void setServiceLevel(InnerRuleEngine serviceLevel) {
        this.serviceLevel = serviceLevel;
    }

    public InnerRuleEngine getMethodLevel() {
        return this.methodLevel;
    }

    public void setMethodLevel(InnerRuleEngine methodLevel) {
        this.methodLevel = methodLevel;
    }

    public InnerRuleEngine getArgsLevel() {
        return this.argsLevel;
    }

    public void setArgsLevel(InnerRuleEngine argsLevel) {
        this.argsLevel = argsLevel;
    }
}