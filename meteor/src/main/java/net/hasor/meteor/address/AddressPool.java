/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.EFunction;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.address.route.ArgsKey;
import net.hasor.meteor.address.route.DefaultArgsKey;

/**
 * 服务地址池
 * <p>路由策略：随机选址
 * <p>流控规则：服务级、方法级、参数级
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年9月12日
 */
public class AddressPool {
    protected static final Logger                     logger = LoggerFactory.getLogger(AddressPool.class);
    protected final        Map<String, AddressBucket> addressPool;
    protected final        Object                     poolLock;
    private final          String                     unitName;
    private final          long                       invalidWakeupTimeMs;
    private final          AddressCacheResult         rulerCache;
    private final          ArgsKey                    argsKey;

    public AddressPool() {
        this("default", 120000);
    }

    public AddressPool(String unitName, long invalidWakeupTimeMs) {
        logger.info("AddressPool unitName at " + unitName);
        this.addressPool = new ConcurrentHashMap<>();
        this.unitName = unitName;
        this.invalidWakeupTimeMs = Math.max(30000, invalidWakeupTimeMs);
        this.rulerCache = new AddressCacheResult(this);
        this.poolLock = new Object();
        this.argsKey = new DefaultArgsKey();
    }

    public AddressBucket getBucket(String serviceID) {
        return this.addressPool.getOrDefault(serviceID, null);
    }

    /**
     * 获取本机所属单元（虚机房 or 集群）
     * @return 返回单元名（虚机房 or 集群）
     */
    public String getUnitName() {
        return this.unitName;
    }

    /**
     * 所有服务地址快照功能，该接口获得的数据不可以进行写操作。通过这个接口可以获得到此刻地址池中所有服务的：
     * <ol>
     * <li>原始服务地址列表，以{serviceID}_ALL作为key</li>
     * <li>本单元服务地址列表，以{serviceID}_UNIT作为key</li>
     * <li>不可用服务地址列表，以{serviceID}_INVALID作为key</li>
     * <li>所有可用服务地址列表，以{serviceID}作为key</li>
     * <ol>
     * 并不是单元化的列表中是单元化规则计算的结果,规则如果失效单元化列表中讲等同于 all
     */
    public Map<String, List<InterAddress>> allServiceAddressToSnapshot() {
        Map<String, List<InterAddress>> snapshot = new HashMap<>();
        synchronized (this.poolLock) {
            for (String key : this.addressPool.keySet()) {
                AddressBucket bucket = this.addressPool.get(key);
                snapshot.put(key + "_ALL", bucket.getAllAddresses());
                snapshot.put(key + "_UNIT", bucket.getLocalUnitAddresses());
                snapshot.put(key + "_INVALID", bucket.getInvalidAddresses());
                snapshot.put(key, bucket.getAvailableAddresses());
            }
        }
        return snapshot;
    }

    /**
     * 获取地址池中注册的服务列表。
     * @return 返回地址池中注册的服务列表。
     */
    public Set<String> getBucketNames() {
        Set<String> duplicate;
        synchronized (this.poolLock) {
            duplicate = new HashSet<>(this.addressPool.keySet());
        }
        return duplicate;
    }

    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHost 追加更新的地址。
     */
    public void appendStaticAddress(String serviceID, InterAddress newHost) {
        List<InterAddress> newHostSet = Collections.singletonList(newHost);
        this.appendStaticAddress(serviceID, newHostSet);
    }

    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHostSet 追加更新的地址。
     */
    public void appendStaticAddress(String serviceID, Collection<InterAddress> newHostSet) {
        this._appendAddress(serviceID, newHostSet, AddressTypeEnum.Static);
    }

    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHost 追加更新的地址。
     */
    public void appendAddress(String serviceID, InterAddress newHost) {
        List<InterAddress> newHostSet = Collections.singletonList(newHost);
        this.appendAddress(serviceID, newHostSet);
    }

    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * @param serviceID 服务ID。
     * @param newHostSet 追加更新的地址。
     */
    public void appendAddress(String serviceID, Collection<InterAddress> newHostSet) {
        this._appendAddress(serviceID, newHostSet, AddressTypeEnum.Dynamic);
    }

    private void _appendAddress(String serviceID, Collection<InterAddress> newHostSet, AddressTypeEnum type) {
        String hosts = StringUtils.join(newHostSet.toArray(), ", ");
        logger.info("updateAddress of service " + serviceID + " , new Address set = " + hosts);
        //1.AddressBucketd
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            /*在并发情况下,invalidAddress可能正打算读取AddressBucket,因此要锁住poolLock*/
            synchronized (this.poolLock) {
                AddressBucket newBucket = new AddressBucket(serviceID, this.unitName);
                //newBucket.addObserver(this.refreshCacheNotify);
                bucket = this.addressPool.putIfAbsent(serviceID, newBucket);
                if (bucket == null) {
                    bucket = newBucket;
                }
                logger.info("newBucket " + bucket);
            }
        }
        //2.新增服务
        bucket.newAddress(newHostSet, type);
        bucket.refreshAddress();//局部更新
        this.rulerCache.reset();
    }

    /**
     * 将服务的地址设置成临时失效状态。
     * 置为失效，失效并不意味着永久的。
     * @param address 失效的地址。
     */
    public void invalidAddress(InterAddress address) {
        /*在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            Set<String> keySet = this.addressPool.keySet();
            for (String bucketKey : keySet) {
                logger.info("serviceID =" + bucketKey + " ,invalid address = " + address + " ,bucket is not exist.");
                AddressBucket bucket = this.addressPool.get(bucketKey);
                bucket.invalidAddress(address, this.invalidWakeupTimeMs);
                bucket.refreshAddress();
            }
        }
        this.rulerCache.reset();
    }

    /**
     * 将服务的地址设置成临时失效状态，把地址从服务的地址本中彻底删除。
     * @param serviceID 服务ID。
     * @param invalidAddress 将要删除的地址。
     */
    public void removeAddress(String serviceID, InterAddress invalidAddress) {
        this.removeAddress(serviceID, Collections.singletonList(invalidAddress));
    }

    /**
     * 将服务的地址设置成临时失效状态，把地址从服务的地址本中彻底删除。
     * @param serviceID 服务ID。
     * @param invalidAddressSet 将要删除的地址。
     */
    public void removeAddress(String serviceID, Collection<InterAddress> invalidAddressSet) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            logger.info("serviceID =" + serviceID + " ,bucket is not exist.");
            return;
        }
        StringBuilder strBuilder = new StringBuilder();
        if (invalidAddressSet == null || invalidAddressSet.isEmpty()) {
            strBuilder.append("empty.");
        } else {
            for (InterAddress invalidAddress : invalidAddressSet) {
                strBuilder.append(invalidAddress.toHostSchema() + ",");
                bucket.removeAddress(invalidAddress);
                bucket.refreshAddress();
                this.rulerCache.reset();
            }
        }
        logger.info("serviceID =" + serviceID + " ,remove invalidAddress = " + strBuilder + " ,wait " + this.invalidWakeupTimeMs + " -> active.");
    }

    /** 从所有服务中删除这个地址 */
    public void removeAddress(InterAddress address) {
        /* 在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock */
        synchronized (this.poolLock) {
            Set<String> keySet = this.addressPool.keySet();
            for (String bucketKey : keySet) {
                AddressBucket bucket = this.addressPool.get(bucketKey);
                if (bucket == null) {
                    return;
                }
                logger.debug("service " + bucketKey + " removeAddress.");
                bucket.removeAddress(address);
            }
        }
        this.rulerCache.reset();
    }

    /**
     * 从地址池中，删除指定服务的地址本。
     * @param serviceID 服务ID。
     */
    public boolean removeBucket(String serviceID) {
        boolean removed;
        synchronized (this.poolLock) {
            removed = this.addressPool.remove(serviceID) != null;
        }
        if (removed) {
            this.rulerCache.reset();
        }
        return removed;
    }

    /** 刷新服务的地址本，使其使用全新的地址本 */
    public void refreshAddress(String serviceID, List<InterAddress> addressList) {
        /*在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            AddressBucket bucket = this.addressPool.get(serviceID);
            if (bucket == null) {
                return;
            }
            logger.debug("service " + serviceID + " refreshCache.");
            bucket.refreshAddressToNew(addressList);//刷新地址计算结果
        }
        this.rulerCache.reset();
    }

    /** 刷新地址缓存 */
    public void refreshAddressCache() {
        /*在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            Set<String> keySet = this.addressPool.keySet();
            for (String bucketKey : keySet) {
                AddressBucket bucket = this.addressPool.get(bucketKey);
                if (bucket == null) {
                    return;
                }
                logger.debug("service " + bucketKey + " refreshCache.");
                bucket.refreshAddress();//刷新地址计算结果
            }
        }
        this.rulerCache.reset();
    }

    /**
     * 从服务地址本中获取一条可用的地址。<p>当一个服务具有多个地址的情况下，为了保证公平性地址池采取了随机选取的方式（路由策略：随机选址）
     * <ul>
     *  <li>如果地址池中没有定义这个服务的Bucket，那么将会返回一个null。</li>
     *  <li>如果地址本或者地址池上配置了流控机制，那么选择到的地址将会被限制固定的速率，进而限制nextAddress方法的整个QPS。</li>
     *  <li>默认情况下地址的获取，会受到路由规则、流控规则的影响。</li>
     * </ul>
     * 当地址获取和地址更新同时进行时候，不需要保证瞬时的一致性，只要保证最终一致性就好。
     * @param serviceID 服务id。
     * @param methodName 调用该服务的方法名。
     * @param args 方法调用时用到的参数。
     * @return 返回可以使用的地址。
     */
    public InterAddress nextAddress(String serviceID, String methodName, Object[] args) {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Address selection interrupted");
            }
            AddressBucket bucket = this.addressPool.get(serviceID);
            if (bucket == null) {
                return null;
            }
            List<InterAddress> addresses = this.rulerCache.getAddressList(serviceID, methodName, args);
            if (addresses == null || addresses.isEmpty()) {
                return null;
            }
            FlowControlRef flowControl = bucket.getFlowControlRef();
            InterAddress address = flowControl.randomFlowControl.getServiceAddress(addresses);
            if (flowControl.speedFlowControl.callCheck(serviceID, methodName, address)) {
                return address;
            }

            // Permit cancellation and rule/address replacement while waiting for quota.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    protected ArgsKey getArgsKey() {
        return this.argsKey;
    }

    /** 获取地址路由规则引用。 */
    protected RuleRef getRefRule(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        RuleRef ruleRef = null;
        if (bucket != null && bucket.getRuleRef() != null) {
            ruleRef = bucket.getRuleRef();
        }
        return ruleRef;
    }

    @Override
    public String toString() {
        return "AddressPool[" + this.unitName + "]";
    }

    /** 更新服务地址本计算规则（服务级） */
    public boolean updateServiceRoute(String serviceID, String scriptBody) {
        return this.updateRoute(serviceID, RouteTypeEnum.ServiceLevel, scriptBody);
    }

    /** 更新服务地址本计算规则（方法级） */
    public boolean updateMethodRoute(String serviceID, String scriptBody) {
        return this.updateRoute(serviceID, RouteTypeEnum.MethodLevel, scriptBody);
    }

    /** 更新服务地址本计算规则（参数级） */
    public boolean updateArgsRoute(String serviceID, String scriptBody) {
        return this.updateRoute(serviceID, RouteTypeEnum.ArgsLevel, scriptBody);
    }

    /**
     * 更新服务的流控规则。
     * @param serviceID 应用到的服务。
     * @param flowControl 流控规则
     */
    public boolean updateFlowControl(String serviceID, String flowControl) {
        if (StringUtils.isBlank(serviceID)) {
            return false;
        }

        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            logger.warn("update flowControl service=" + serviceID + " -> AddressBucket not exist.");
            return false;
        }
        logger.info("update flowControl service=" + serviceID + " -> update ok");
        if (!bucket.updateFlowControl(flowControl)) {
            return false;
        }

        this.refreshAddressCache();
        return true;
    }

    /**
     * 更新某个服务的路由规则脚本。
     * @param serviceID 要更新的服务。
     * @param routeType 更新的路由规则类型。
     * @param script 路由规则脚本内容。
     */
    public boolean updateRoute(String serviceID, RouteTypeEnum routeType, String script) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            logger.warn("update rules service=" + serviceID + " -> AddressBucket not exist.");
            return false;
        }

        logger.info("update rules service=" + serviceID + " -> update ok");
        if (!bucket.updateRoute(routeType, script)) {
            return false;
        }

        this.refreshAddressCache();
        return true;
    }

    /** 获取服务级路由脚本 */
    public String serviceRoute(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return getServiceRouteByRef(bucket.getRuleRef());
    }

    /** 获取方法级路由脚本 */
    public String methodRoute(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return getMethodRouteByRef(bucket.getRuleRef());
    }

    /** 获取参数级路由脚本 */
    public String argsRoute(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return getArgsRouteByRef(bucket.getRuleRef());
    }

    /** 获取流控脚本 */
    public String flowControl(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return getFlowControlByRef(bucket.getFlowControlRef());
    }

    /** 获取所有地址（包括本地的和无效的） */
    public List<InterAddress> queryAllAddresses(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return Collections.unmodifiableList(bucket.getAllAddresses());
    }

    /** 获取计算之后可用的地址 */
    public List<InterAddress> queryAvailableAddresses(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return Collections.unmodifiableList(bucket.getAvailableAddresses());
    }

    /** 失效地址 */
    public List<InterAddress> queryInvalidAddresses(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return Collections.unmodifiableList(bucket.getInvalidAddresses());
    }

    /** 获取计算之后同一单元地址 */
    public List<InterAddress> queryLocalUnitAddresses(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        return Collections.unmodifiableList(bucket.getLocalUnitAddresses());
    }

    private static String getFlowControlByRef(FlowControlRef ruleRef) {
        if (ruleRef == null || ruleRef.flowControlScript == null) {
            return null;
        }
        return ruleRef.flowControlScript;
    }

    private static String getArgsRouteByRef(RuleRef ruleRef) {
        if (ruleRef == null || ruleRef.getArgsLevel() == null) {
            return null;
        }
        return ruleRef.getArgsLevel().getScript();
    }

    private static String getMethodRouteByRef(RuleRef ruleRef) {
        if (ruleRef == null || ruleRef.getMethodLevel() == null) {
            return null;
        }
        return ruleRef.getMethodLevel().getScript();
    }

    private static String getServiceRouteByRef(RuleRef ruleRef) {
        if (ruleRef == null || ruleRef.getServiceLevel() == null) {
            return null;
        }
        return ruleRef.getServiceLevel().getScript();
    }

    protected <R, E extends Throwable> R poolLock(EFunction<AddressPool, R, E> foo) throws E {
        synchronized (this.poolLock) {
            return foo.eApply(this);
        }
    }
}