/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.address.route;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.cobble.setting.MergedSettings;
import net.hasor.cobble.setting.Settings;
import net.hasor.cobble.setting.provider.StreamType;
import net.hasor.rsf.address.route.random.RandomFlowControl;
import net.hasor.rsf.address.route.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.unit.UnitFlowControl;

/**
 * 路由规则解析器
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2015年3月29日
 */
public class RuleParser {
    protected     Logger                              logger      = LoggerFactory.getLogger(getClass());
    private final Map<String, Supplier<AbstractRule>> ruleTypeMap = new HashMap<>();

    public RuleParser() {
        this.ruleTypeMap.put("random", RandomFlowControl::new);// 随机选址
        this.ruleTypeMap.put("speed", SpeedFlowControl::new);  // QoS速率
        this.ruleTypeMap.put("unit", UnitFlowControl::new);    // 单元化地址本计算
    }

    /** 解析规则文本为{@link Settings} */
    public Rule ruleSettings(String rawRoute) {
        if (StringUtils.isBlank(rawRoute)) {
            logger.info("rule raw format error.");
            return null;
        }

        try {
            MergedSettings ruleSettings = new MergedSettings();
            ruleSettings.loadStringBody("<xml>" + rawRoute + "</xml>", StreamType.Xml);
            return ruleSettings(ruleSettings);
        } catch (Exception e) {
            logger.error("rule raw format error. -> " + e.getMessage(), e);
        }
        return null;
    }

    /** 解析规则文本为{@link Settings} */
    public Rule ruleSettings(Settings ruleSettings) {
        if (ruleSettings == null) {
            logger.info("ruleSettings is null.");
            return null;
        }

        String ruleID = ruleSettings.getString("flowControl.type");
        if (StringUtils.isBlank(ruleID)) {
            return null;
        }

        ruleID = ruleID.trim().toLowerCase(Locale.ROOT);
        Supplier<AbstractRule> factory = this.ruleTypeMap.get(ruleID);
        if (factory == null) {
            logger.info("rule type of '" + ruleID + "' is undefined.");
            return null;
        }

        AbstractRule ruleObject = factory.get();
        boolean ruleEnable = ruleSettings.getBoolean("flowControl.enable", false);
        logger.info("process rule '" + ruleID + "' -> " + ruleEnable);

        ruleObject.setRouteID(ruleID);
        ruleObject.setRouteBody(ruleSettings.getNode("flowControl").toXml());
        ruleObject.enable(ruleEnable);
        ruleObject.parseControl(ruleSettings);
        return ruleObject;
    }

    @Override
    public String toString() {
        return "RuleParser Types:" + ruleTypeMap.keySet();
    }
}
