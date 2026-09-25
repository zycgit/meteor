/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.route.random;
import java.util.List;
import java.util.Random;
import net.hasor.cobble.setting.Settings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.route.AbstractRule;

/**
 * 对于一组备选地址，通过随机的方式选取其中一个地址。
 * <pre>
 * 配置实例：
 * &lt;flowControl enable="true|false" type="random"&gt;
 * &lt;/flowControl&gt;
 * </pre>
 * @version : 2015年4月6日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RandomFlowControl extends AbstractRule {
    private Random random = new Random();

    @Override
    public void parseControl(Settings settings) {
        this.random = new Random(System.currentTimeMillis());
    }

    /**使用随机规则选取备选地址中的一个地址。*/
    public InterAddress getServiceAddress(List<InterAddress> addresses) {
        if ((addresses == null) || (addresses.size() == 0)) {
            return null;
        }
        int size = addresses.size();
        if (size == 1) {
            return addresses.get(0);
        }
        int index = this.random.nextInt(size);
        return addresses.get(index);
    }
}
