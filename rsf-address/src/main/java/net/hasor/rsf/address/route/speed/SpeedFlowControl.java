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
package net.hasor.rsf.address.route.speed;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.QoSBucket;
import net.hasor.cobble.setting.Settings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.route.AbstractRule;

/**
 * 基于QoS的速率控制规则。
 * <pre>
 * 配置实例：
 * &lt;flowControl enable="true|false" type="speed"&gt;
 *   &lt;action&gt;service|method|address&lt;/action&gt;
 *   &lt;rate&gt;20&lt;/rate&gt;             &lt;!-- 稳态速率 --&gt
 *   &lt;peak&gt;100&lt;/peak&gt;            &lt;!-- 峰值速率 --&gt
 *   &lt;timeWindow&gt;10&lt;/timeWindow&gt; &lt;!-- 时间窗口 --&gt
 * &lt;/flowControl&gt;
 * </pre>
 * 解释：根据action的配置决定RPC调用速率。
 */
public class SpeedFlowControl extends AbstractRule {
    private QoSActionEnum                    action;
    private int                              rate       = 20;
    private int                              peak       = 200;
    private int                              timeWindow = 10;
    private QoSBucket                        defaultQoSBucket;
    private ConcurrentMap<String, QoSBucket> qosBucketMap;

    public void parseControl(Settings settings) {
        this.enable(settings.getBoolean("flowControl.enable", false));
        this.action = settings.getEnum("flowControl.action", QoSActionEnum.class);
        this.rate = settings.getInteger("flowControl.rate", 20);
        this.peak = settings.getInteger("flowControl.peak", 200);
        this.timeWindow = settings.getInteger("flowControl.timeWindow", 10);
        this.qosBucketMap = new ConcurrentHashMap<>();

        if (!this.enable()) {
            return;
        }
        if (this.action == null) {
            throw new IllegalArgumentException("speed action is required");
        }
        QoSBucket qosBucket = this.createQoSBucket(null);
        if (!qosBucket.validate()) {
            throw new IllegalArgumentException("Invalid speed rate, peak or time window");
        }
        defaultQoSBucket = qosBucket;
    }

    public boolean callCheck(String serviceID, String methodName, InterAddress doCallAddress) {
        if (!this.enable()) {
            return true;
        }

        String key = null;
        switch (this.action) {
            case Address:
                key = doCallAddress.toString();
                break;
            case Method:
                key = serviceID.length() + ":" + serviceID + methodName;
                break;
            case Service:
                key = serviceID;
                break;
        }

        if (key == null) {
            return true;
        }

        QoSBucket qos = this.qosBucketMap.get(key);
        if (qos == null) {
            synchronized (this) {
                qos = this.qosBucketMap.get(key);
                if (qos == null) {
                    qos = this.createQoSBucket(key);
                    this.qosBucketMap.put(key, qos);
                }
            }
        }
        return qos.check();
    }

    protected QoSBucket createQoSBucket(String qosKey) {
        QoSBucket qosBucket = new QoSBucket(this.rate, this.peak, this.timeWindow);

        if (StringUtils.isBlank(qosKey)) {
            logger.info("create default " + qosBucket);
        } else {
            logger.info("create " + qosKey + " " + qosBucket);
        }

        return qosBucket;
    }

    public static SpeedFlowControl defaultControl() {
        SpeedFlowControl flowControl = new SpeedFlowControl();
        flowControl.action = QoSActionEnum.Service; // 速率控制方式：每服务、每方法、每地址
        flowControl.rate = 2000;    // 稳态速率
        flowControl.peak = 5000;    // 峰值速率
        flowControl.timeWindow = 10;//时间窗口
        return flowControl;
    }
}
