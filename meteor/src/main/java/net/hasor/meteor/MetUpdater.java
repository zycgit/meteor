/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Collection;
import java.util.List;
import net.hasor.meteor.address.InterAddress;

/**
 * 服务配置更新器。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetUpdater {
    /**更新服务地址本计算规则（服务级）*/
    boolean updateServiceRoute(String serviceID, String scriptBody);

    /**更新服务地址本计算规则（服务级）*/
    String serviceRoute(String serviceID);

    /**更新本地方法级地址计算脚本。*/
    boolean updateMethodRoute(String serviceID, String scriptBody);

    /**更新本地方法级地址计算脚本。*/
    String methodRoute(String serviceID);

    /**更新本地参数级地址计算脚本。*/
    boolean updateArgsRoute(String serviceID, String scriptBody);

    /**更新本地参数级地址计算脚本。*/
    String argsRoute(String serviceID);

    /**更新服务路由策略*/
    boolean updateFlowControl(String serviceID, String flowControl);

    /**更新服务路由策略*/
    String flowControl(String serviceID);

    /**获取所有地址（包括本地的和无效的）。*/
    List<InterAddress> queryAllAddresses(String serviceID);

    /**获取计算之后可用的地址。*/
    List<InterAddress> queryAvailableAddresses(String serviceID);

    /**失效地址。*/
    List<InterAddress> queryInvalidAddresses(String serviceID);

    /**获取计算之后同一单元地址。*/
    List<InterAddress> queryLocalUnitAddresses(String serviceID);

    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHostSet 追加更新的地址。
     */
    void appendAddress(String serviceID, Collection<InterAddress> newHostSet);

    /**
     * 新增或追加更新服务静态本信息,静态服务地址是永久'有效'地址。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHostSet 追加更新的地址。
     */
    void appendStaticAddress(String serviceID, Collection<InterAddress> newHostSet);

    /**刷新服务的地址本，使其使用全新的地址本。*/
    void refreshAddress(String serviceID, List<InterAddress> addressList);

    /**使用新的地址本替换已有的地址本。*/
    void refreshAddressCache();

    /**
     * 将服务的地址设置成临时失效状态，把地址从服务的地址本中彻底删除。
     * @param serviceID 服务ID。
     * @param invalidAddress 将要删除的地址。
     */
    void removeAddress(String serviceID, InterAddress invalidAddress);

    /**
     * 将服务的地址设置成临时失效状态，把地址从服务的地址本中彻底删除。
     * @param serviceID 服务ID。
     * @param invalidAddressSet 将要删除的地址。
     */
    void removeAddress(String serviceID, Collection<InterAddress> invalidAddressSet);

    /**某一个地址不可用了，从所有服务中删除这个地址*/
    void removeAddress(InterAddress address);
}