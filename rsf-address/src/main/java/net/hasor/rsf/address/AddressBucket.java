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
package net.hasor.rsf.address;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.rsf.address.route.unit.UnitFlowControl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Observable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 描述：用于接收地址更新同时也用来计算有效和无效地址。
 * 也负责提供服务地址列表集，负责分类存储和处理同一个服务的各种类型的服务地址数据，比如：
 * <ol>
 *  <li>同单元服务地址</li>
 *  <li>有效服务地址</li>
 *  <li>不可用服务地址</li>
 *  <li>全部服务地址</li>
 * </ol>
 * 所有对服务地址的进一 步处理都需要使用{@link #getAvailableAddresses()}获得的地址列表。
 * 如果应用了本地机房策略，则本地
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public class AddressBucket extends Observable {
    public static final    String                                        LOGGER_NAME   = "rsf-address";
    protected static final Logger                                        addressLogger = LoggerFactory.getLogger(LOGGER_NAME);
    protected static final Logger                                        logger        = LoggerFactory.getLogger(AddressBucket.class);
    //流控&路由
    private volatile       FlowControlRef                                flowControlRef;     //默认流控规则引用
    private volatile       RuleRef                                       ruleRef;
    //原始数据
    private final          String                                        serviceID;          //服务ID
    private final          String                                        unitName;           //服务所属单元
    private final          List<InterAddress>                            allAddressList;     //所有备选地址
    private final          List<InterAddress>                            staticAddressList;  //不会失效的地址（即使是注册中心推送也不会失效）
    //运行时动态更新的地址
    private final          ConcurrentMap<InterAddress, InnerInvalidInfo> invalidAddresses;   //失效状态统计信息
    //下面时计算出来的数据
    private                List<InterAddress>                            localUnitAddresses; //本单元地址
    private                List<InterAddress>                            availableAddresses; //所有可用地址（包括本地单元）

    public AddressBucket(String serviceID, String unitName) {
        this.flowControlRef = FlowControlRef.defaultRef();
        this.ruleRef = new RuleRef(null);
        this.serviceID = serviceID;
        this.unitName = unitName;
        this.allAddressList = new CopyOnWriteArrayList<>();
        this.staticAddressList = new CopyOnWriteArrayList<>();
        this.invalidAddresses = new ConcurrentHashMap<>();
        this.localUnitAddresses = new ArrayList<>();
        this.availableAddresses = new ArrayList<>();
        this.refreshAddress();
    }

    public String getServiceID() {
        return serviceID;
    }

    public FlowControlRef getFlowControlRef() {
        return this.flowControlRef;
    }

    public RuleRef getRuleRef() {
        return this.ruleRef;
    }

    /** 获取所有地址（包括本地的和无效的） */
    public synchronized List<InterAddress> getAllAddresses() {
        return new ArrayList<>(this.allAddressList);
    }

    /** 获取静态地址 */
    public synchronized List<InterAddress> getStaticAddresses() {
        return new ArrayList<>(this.staticAddressList);
    }

    /** 获取计算之后可用的地址 */
    public synchronized List<InterAddress> getAvailableAddresses() {
        return new ArrayList<>(this.availableAddresses);
    }

    /** 失效地址 */
    public synchronized List<InterAddress> getInvalidAddresses() {
        return new ArrayList<>(this.invalidAddresses.keySet());
    }

    /** 获取计算之后同一单元地址 */
    public synchronized List<InterAddress> getLocalUnitAddresses() {
        return this.localUnitAddresses;
    }

    /** 新增地址支持动态新增 */
    public void newAddress(Collection<InterAddress> newHostSet, AddressTypeEnum type) {
        StringBuilder strBuffer = new StringBuilder();
        for (InterAddress addr : newHostSet) {
            strBuffer.append(addr.toHostSchema());
            strBuffer.append(",");
        }
        addressLogger.info("newAddress(" + serviceID + ") -> " + type.name() + ", [" + strBuffer + "].");

        List<InterAddress> newAddress = new ArrayList<>();
        List<InterAddress> newStaticAddress = new ArrayList<>();
        List<InterAddress> toAvailable = new ArrayList<>();
        for (InterAddress newHost : newHostSet) {
            if (newHost == null) {
                continue;
            }
            //1.保证不要重复添加。
            boolean doAdd = true;
            for (InterAddress hasAddress : this.allAddressList) {
                if (newHost.equals(hasAddress)) {
                    doAdd = false;
                    break;
                }
            }
            //2.确定是否需要再次激活。
            for (InterAddress hasAddress : this.invalidAddresses.keySet()) {
                if (newHost.equals(hasAddress)) {
                    toAvailable.add(newHost);
                }
            }

            if (doAdd) {
                if (AddressTypeEnum.Static.equals(type)) {
                    newStaticAddress.add(newHost);
                }
                newAddress.add(newHost);
            }
        }

        //添加新地址
        this.allAddressList.addAll(newAddress);
        this.staticAddressList.addAll(newStaticAddress);
        //激活已经失效的地址
        for (InterAddress hasAddress : toAvailable) {
            this.invalidAddresses.remove(hasAddress);
        }
        this.refreshAvailableAddress();
    }

    /**
     * 将地址置为失效的(对于静态地址,该方法无效)。
     * @param newInvalid 失效的地址。
     * @param timeoutMs 失效时长
     */
    public void invalidAddress(InterAddress newInvalid, long timeoutMs) {
        if (this.staticAddressList.contains(newInvalid)) {
            addressLogger.warn("invalidAddress(" + serviceID + ") -> targetAddress =" + newInvalid + " ,addr is static.");
            return;//对于静态地址,该方法无效
        }
        if (!this.allAddressList.contains(newInvalid)) {
            addressLogger.warn("invalidAddress(" + serviceID + ") -> targetAddress =" + newInvalid + " ,addr is not exist.");
            return;
        }
        InnerInvalidInfo invalidInfo = this.invalidAddresses.putIfAbsent(newInvalid, new InnerInvalidInfo(timeoutMs));
        if (invalidInfo != null) {
            addressLogger.info("invalidAddress(" + serviceID + ") -> targetAddress =" + newInvalid + " ,timeoutMs =" + timeoutMs);
            invalidInfo.invalid(timeoutMs);
        } else {
            try {
                synchronized (this) {
                    refreshAvailableAddress();
                }
            } catch (Exception e) {
                logger.error("address(" + serviceID + ") -> invalid Address error -> " + e.getMessage(), e);
            }
        }
    }

    /**
     * 将地址从地址本中删除。
     * @param address 要被删除的地址。
     */
    public void removeAddress(InterAddress address) {
        if (!this.allAddressList.contains(address)) {
            addressLogger.warn("removeAddress(" + serviceID + ") -> targetAddress =" + address + " ,addr is not exist.");
            return;
        } else {
            addressLogger.info("removeAddress(" + serviceID + ") -> targetAddress =" + address);
        }
        this.allAddressList.remove(address);
        this.staticAddressList.remove(address);
        this.invalidAddresses.remove(address);
        synchronized (this) {
            refreshAvailableAddress();
        }
    }

    /** 刷新地址计算结果 */
    public void refreshAddress() {
        synchronized (this) {
            refreshAvailableAddress();
        }
    }

    public void refreshAddressToNew(List<InterAddress> addressList) {
        if (addressList == null || addressList.isEmpty()) {
            return;
        }
        synchronized (this) {
            StringBuilder strBuffer = new StringBuilder();
            for (InterAddress addr : addressList) {
                strBuffer.append(addr.toHostSchema());
                strBuffer.append(",");
            }
            addressLogger.info("refreshAddressToNew(" + serviceID + ") -> " + strBuffer);

            this.allAddressList.clear();
            this.allAddressList.addAll(addressList);
            this.invalidAddresses.clear();
            refreshAvailableAddress();
        }
    }

    /** 刷新地址 */
    private void refreshAvailableAddress() {
        //1.计算出有效的地址。
        List<InterAddress> availableList = new ArrayList<>();
        for (InterAddress addressInfo : this.allAddressList) {
            boolean doAdd = true;
            for (InterAddress invalid : this.invalidAddresses.keySet()) {
                if (addressInfo.equals(invalid)) {
                    doAdd = false;
                    break;
                }
            }
            //当失效的地址达到重试时间之后，再次刷新地址时候不被列入失效名单。
            InnerInvalidInfo info = this.invalidAddresses.get(addressInfo);
            if (info != null && info.reTry()) {
                doAdd = true;
            }
            if (doAdd) {
                availableList.add(addressInfo);//有效的
            }
        }

        //2.机房单元化过滤
        List<InterAddress> unitList = availableList;
        if (this.flowControlRef != null && this.flowControlRef.unitFlowControl != null) {
            UnitFlowControl unitFlowControl = this.flowControlRef.unitFlowControl;
            unitList = unitFlowControl.siftUnitAddress(unitName, availableList);
            if (unitList == null || unitList.isEmpty()) {
                unitList = availableList;
            }
            if (!unitFlowControl.isLocalUnit(availableList.size(), unitList.size())) {
                unitList = availableList;
            }
        }

        {
            StringBuilder strBuffer1 = new StringBuilder("[");
            for (InterAddress addr : availableList) {
                strBuffer1.append(addr.toHostSchema());
                strBuffer1.append(",");
            }
            strBuffer1.append("]");
            addressLogger.info("refreshAvailableAddress(" + serviceID + ") -> availableList =" + strBuffer1);
            StringBuilder strBuffer2 = new StringBuilder("[");
            for (InterAddress addr : unitList) {
                strBuffer2.append(addr.toHostSchema());
                strBuffer2.append(",");
            }
            strBuffer2.append("]");
            addressLogger.info("refreshAvailableAddress(" + serviceID + ") -> unitList =" + strBuffer2);
        }

        this.availableAddresses = availableList;
        this.localUnitAddresses = unitList;
        this.notifyObservers(this);//发出消息通知自己的状态变化了
    }

    /** 更新服务的流控规则 */
    public boolean updateFlowControl(String flowControl) {
        if (StringUtils.isBlank(flowControl)) {
            return false;
        }
        FlowControlRef newRef = FlowControlRef.newRef(this.flowControlRef);
        newRef.updateFlowControl(flowControl);
        this.flowControlRef = newRef;
        this.refreshAddress();
        return true;
    }

    /** 更新服务的路由脚本 */
    public boolean updateRoute(RouteTypeEnum routeType, String script) {
        RuleRef newRuleRef = new RuleRef(this.ruleRef);
        boolean updated = RouteTypeEnum.updateScript(routeType, script, newRuleRef);
        if (!updated) {
            logger.warn("address(" + serviceID + ") -> update rules -> no change.");
            return false;
        } else {
            logger.info("address(" + serviceID + ") -> update rules -> update ok");
            this.ruleRef = newRuleRef;
            this.refreshAddress();
            return true;
        }
    }

    @Override
    public String toString() {
        return "AddressBucket - " + this.getServiceID() + //
                " ,unit = " + this.unitName + //
                " ,allAddress size = " + this.allAddressList.size();
    }
}