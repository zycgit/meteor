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
import java.util.*;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.rsf.address.route.ArgsKey;

/**
 * 路由计算结果缓存<br/>
 * 接口级    方法级      参数级
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2015年3月29日
 */
class AddressCacheResult {
    protected static Logger      logger = LoggerFactory.getLogger(AddressCacheResult.class);
    private volatile CacheResult cacheResultRef;
    private final    AddressPool addressPool;
    private          long        revision; // guarded by addressPool.poolLock

    //
    public AddressCacheResult(AddressPool addressPool) {
        this.addressPool = Objects.requireNonNull(addressPool);
    }
    //

    /** 从全部地址中计算执行动态计算并缓存计算结果. */
    public List<InterAddress> getAddressList(String serviceID, String methodName, Object[] args) {
        if (this.cacheResultRef == null) {
            logger.warn("getAddressList fail. resultRef is null.");
            return null;
        }
        List<InterAddress> result = null;
        CacheResult resultRef = this.cacheResultRef;
        //
        //1.获取参数级地址列表
        ArgsKey argsKeyBuilder = addressPool.getArgsKey();
        if (argsKeyBuilder != null) {
            Map<String, Map<String, List<InterAddress>>> methodList = resultRef.argsLevel.get(serviceID);
            if (methodList != null) {
                Map<String, List<InterAddress>> cacheList = methodList.get(methodName);
                if (cacheList != null) {
                    String key = argsKeyBuilder.eval(serviceID, methodName, args);
                    if (key != null) {
                        result = cacheList.get(key);
                    }
                }
            }
        }
        //
        //2.获取方法级地址列表
        if (result == null) {
            Map<String, List<InterAddress>> cacheList = resultRef.methodLevel.get(serviceID);
            if (cacheList != null) {
                result = cacheList.get(methodName);
            }
        }
        //
        //3.获取服务级别地址列表
        if (result == null) {
            result = resultRef.serviceLevel.get(serviceID);
        }
        return result;
    }

    /** 重置缓存结果 */
    public void reset() {
        logger.info("reset addressCache.");
        Snapshot snapshot = this.addressPool.poolLock(pool -> {
            Map<String, BucketSnapshot> buckets = new HashMap<>();
            for (Map.Entry<String, AddressBucket> entry : pool.addressPool.entrySet()) {
                AddressBucket bucket = entry.getValue();
                synchronized (bucket) {
                    buckets.put(entry.getKey(), new BucketSnapshot(bucket.getAvailableAddresses(), bucket.getLocalUnitAddresses(), bucket.getRuleRef()));
                }
            }
            return new Snapshot(++revision, buckets);
        });

        CacheResult cacheResultRef = new CacheResult();
        // Execute user scripts outside the pool lock. A newer reset supersedes this computation.
        for (Map.Entry<String, BucketSnapshot> entry : snapshot.buckets.entrySet()) {
            String serviceID = entry.getKey();
            List<InterAddress> all = entry.getValue().all;
            List<InterAddress> unit = entry.getValue().unit;
            List<String> allStrList = Collections.unmodifiableList(convertToStr(all));
            RuleRef refRule = entry.getValue().rules;

            //1.计算缓存的服务接口级,地址列表
            List<InterAddress> serviceLevelResult = null;
            if (!refRule.getServiceLevel().isEnable()) {
                logger.debug("eval routeScript [ServiceLevel], service " + serviceID + " route undefined.");
            } else {
                List<String> serviceLevelResultStr = evalServiceLevel(serviceID, refRule, allStrList);
                if (serviceLevelResultStr != null && !serviceLevelResultStr.isEmpty()) {
                    serviceLevelResult = convertToAddress(all, serviceLevelResultStr);
                }
            }
            if (serviceLevelResult == null || serviceLevelResult.isEmpty()) {
                serviceLevelResult = unit;/*如果计算结果为空，就使用单元化的地址 -> 如果单元化策略没有配置则单元化地址就是全量地址。*/
            }
            cacheResultRef.serviceLevel.put(serviceID, serviceLevelResult);

            //2.计算缓存的服务方法级,地址列表
            if (!refRule.getMethodLevel().isEnable()) {
                logger.debug("eval routeScript [MethodLevel], service " + serviceID + " route undefined.");
            } else {
                Map<String, List<String>> methodLevelResultStr = evalMethodLevel(serviceID, refRule, allStrList);
                if (methodLevelResultStr != null && !methodLevelResultStr.isEmpty()) {
                    Map<String, List<InterAddress>> methodLevelResult = convertToAddressMethod(all, methodLevelResultStr);
                    cacheResultRef.methodLevel.put(serviceID, methodLevelResult);/*保存计算结果*/
                }
            }

            //3.计算缓存的服务参数级,地址列表
            if (!refRule.getArgsLevel().isEnable()) {
                logger.debug("eval routeScript [ArgsLevel], service " + serviceID + " route undefined.");
            } else if (addressPool.getArgsKey() == null) {
                logger.error("argsKeyBuilder is null , evalArgsLevel failed.");
            } else {
                Map<String, Map<String, List<String>>> argsLevelResultStr = evalArgsLevel(serviceID, refRule, allStrList);
                if (argsLevelResultStr != null && !argsLevelResultStr.isEmpty()) {
                    Map<String, Map<String, List<InterAddress>>> argsLevelResult = convertToAddressArgs(all, argsLevelResultStr);
                    cacheResultRef.argsLevel.put(serviceID, argsLevelResult);/*保存计算结果*/
                }
            }
        }

        logger.debug("switch cacheResultRef.");
        this.addressPool.poolLock(pool -> {
            if (revision == snapshot.revision) {
                this.cacheResultRef = cacheResultRef;
            }
            return null;
        });
    }

    private static class BucketSnapshot {
        final List<InterAddress> all;
        final List<InterAddress> unit;
        final RuleRef            rules;

        BucketSnapshot(List<InterAddress> all, List<InterAddress> unit, RuleRef rules) {
            this.all = all;
            this.unit = unit;
            this.rules = rules;
        }
    }

    private static class Snapshot {
        final long                        revision;
        final Map<String, BucketSnapshot> buckets;

        Snapshot(long revision, Map<String, BucketSnapshot> buckets) {
            this.revision = revision;
            this.buckets = buckets;
        }
    }

    private static Map<String, Map<String, List<InterAddress>>> convertToAddressArgs(List<InterAddress> all, Object value) {
        Map<String, Map<String, List<InterAddress>>> result = new HashMap<>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            Map<String, List<InterAddress>> methods = convertToAddressMethod(all, entry.getValue());
            if (entry.getKey() instanceof String && !methods.isEmpty()) {
                result.put((String) entry.getKey(), methods);
            }
        }
        return result;
    }

    private static Map<String, List<InterAddress>> convertToAddressMethod(List<InterAddress> all, Object value) {
        Map<String, List<InterAddress>> result = new HashMap<>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            List<InterAddress> addresses = convertToAddress(all, entry.getValue());
            if (entry.getKey() instanceof String && !addresses.isEmpty()) {
                result.put((String) entry.getKey(), addresses);
            }
        }
        return result;
    }

    private static List<InterAddress> convertToAddress(List<InterAddress> all, Object value) {
        Set<InterAddress> result = new LinkedHashSet<>();
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        for (Object candidate : (List<?>) value) {
            if (!(candidate instanceof String)) {
                continue;
            }
            for (InterAddress address : all) {
                try {
                    if (address.equalsHost((String) candidate)) {
                        result.add(address);
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static List<String> convertToStr(List<InterAddress> all) {
        List<String> result = new ArrayList<>();
        for (InterAddress address : all) {
            try {
                result.add(address.getHostPort());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * 脚本说明：
     * <pre>入参：
     *  serviceID   （String）
     *  allAddress  （List&lt;String&gt;）
     * 返回值
     *  List&lt;String&gt;
     * 样例：
     *  def List&lt;String&gt; evalAddress(String serviceID,List&lt;String&gt; allAddress)  {
     *      //
     *      //[RSF]sorg.mytest.FooFacse-1.0.0 ，组别：RSF，接口：sorg.mytest.FooFacse，版本：1.0.0
     *      if ( serviceID == "[RSF]sorg.mytest.FooFacse-1.0.0" ) {
     *          return [
     *              "192.168.1.2:8000",
     *              "192.168.1.2:8001",
     *              "192.168.1.3:8000"
     *          ]
     *      }
     *      return null
     *  }</pre>
     */
    private List<String> evalServiceLevel(String serviceID, RuleRef refRule, List<String> all) {
        InnerRuleEngine serviceLevel = refRule.getServiceLevel();
        if (serviceLevel == null) {
            return null;
        }
        try {
            return (List<String>) serviceLevel.runRule(serviceID, all);
        } catch (Throwable e) {
            logger.error("evalServiceLevel error ,message = " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 脚本说明：
     * <pre>入参：
     *  serviceID   （String）
     *  allAddress  （List&lt;String&gt;）
     * 返回值
     *  Map&lt;String,List&lt;String&gt;&gt;
     * 样例：
     *  def Map&lt;String,List&lt;String&gt;&gt; evalAddress(String serviceID,List&lt;String&gt; allAddress)  {
     *      //
     *      //[RSF]sorg.mytest.FooFacse-1.0.0 ---- Group=RSF, Name=sorg.mytest.FooFacse, Version=1.0.0
     *      if ( serviceID == "[RSF]sorg.mytest.FooFacse-1.0.0" ) {
     *          return [
     *              "println":[
     *                  "192.168.1.2:8000",
     *                  "192.168.1.2:8001",
     *                  "192.168.1.3:8000"
     *              ],
     *              "sayEcho":[
     *                  "192.168.1.2:8000",
     *              ],
     *              "testUserTag":[
     *                  "192.168.1.2:8000",
     *                  "192.168.1.3:8000"
     *              ]
     *          ]
     *      }
     *      return null
     *  }</pre>
     */
    private Map<String, List<String>> evalMethodLevel(String serviceID, RuleRef refRule, List<String> all) {
        InnerRuleEngine methodLevel = refRule.getMethodLevel();
        if (methodLevel == null) {
            return null;
        }
        try {
            return (Map<String, List<String>>) methodLevel.runRule(serviceID, all);
        } catch (Throwable e) {
            logger.error("evalMethodLevel error ,message = " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 脚本说明：
     * <pre>入参：
     *  serviceID   （String）
     *  allAddress  （List&lt;String&gt;）
     * 返回值
     *  Map&lt;String, Map&lt;String, List&lt;String&gt;&gt;&gt;
     * 样例：
     *  def Map&lt;String, Map&lt;String, List&lt;String&gt;&gt;&gt; evalAddress(String serviceID,List&lt;String&gt; allAddress)  {
     *      //
     *      //[RSF]sorg.mytest.FooFacse-1.0.0 ---- Group=RSF, Name=sorg.mytest.FooFacse, Version=1.0.0
     *      if ( serviceID == "[RSF]sorg.mytest.FooFacse-1.0.0" ) {
     *          return [
     *              "sayEcho":[
     *                  "sayTo_etc1":[
     *                      "202.168.17.10:8000",
     *                      "202.168.17.11:8000"
     *                  ],
     *                  "sayTo_etc2":[
     *                      "192.168.137.10:8000",
     *                      "192.168.137.11:8000"
     *                  ]],
     *              "testUserTag":[
     *                  "server_3":[
     *                      "192.168.1.3:8000"
     *                  ],
     *                  "server_4":[
     *                      "192.168.1.4:8000"
     *                  ]
     *              ]
     *          ]
     *      }
     *      return null
     *  }</pre>
     */
    private Map<String, Map<String, List<String>>> evalArgsLevel(String serviceID, RuleRef refRule, List<String> all) {
        InnerRuleEngine argsLevel = refRule.getArgsLevel();
        if (argsLevel == null) {
            return null;
        }
        try {
            return (Map<String, Map<String, List<String>>>) argsLevel.runRule(serviceID, all);
        } catch (Throwable e) {
            logger.error("evalArgsLevel error ,message = " + e.getMessage(), e);
            return null;
        }
    }

    private static class CacheResult {
        public final Map<String, List<InterAddress>>                           serviceLevel; //服务接口级
        public final Map<String, Map<String, List<InterAddress>>>              methodLevel;  //方法级
        public final Map<String, Map<String, Map<String, List<InterAddress>>>> argsLevel;    //参数级

        public CacheResult() {
            this.serviceLevel = new HashMap<>();// 服务接口级
            this.methodLevel = new HashMap<>(); // 方法级
            this.argsLevel = new HashMap<>();   // 参数级
        }
    }
}