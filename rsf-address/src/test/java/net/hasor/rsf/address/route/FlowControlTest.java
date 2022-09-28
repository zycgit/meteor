package net.hasor.rsf.address.route;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.route.random.RandomFlowControl;
import net.hasor.rsf.address.route.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.unit.UnitFlowControl;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FlowControlTest {
    private static final Random RANDOM = new Random(System.currentTimeMillis());

    private List<InterAddress> addressList() {
        List<InterAddress> addresses = new ArrayList<>();
        addresses.add(new InterAddress("192.168.137.1", 8000, "etc2"));
        addresses.add(new InterAddress("192.168.137.2", 8000, "etc2"));
        addresses.add(new InterAddress("192.168.1.3", 8000, "etc3"));
        addresses.add(new InterAddress("192.168.1.4", 8000, "etc3"));
        addresses.add(new InterAddress("192.168.1.5", 8000, "etc3"));
        return addresses;
    }

    private RuleParser getRuleParser() {
        return new RuleParser();
    }

    private Map<String, AtomicInteger> doRunSpeedTest(long startTime, SpeedFlowControl rule, List<InterAddress> doCallAddress, List<String> serviceIds, List<String> serviceMethods) {
        int totalCnt = 0;
        Map<String, AtomicInteger> run = new HashMap<>();

        while ((System.currentTimeMillis() - startTime) <= 2000) {
            String useService = serviceIds.get(RANDOM.nextInt(serviceIds.size()));
            String useMethod = serviceMethods.get(RANDOM.nextInt(serviceMethods.size()));
            InterAddress useInterAddress = doCallAddress.get(RANDOM.nextInt(doCallAddress.size()));

            if (rule.callCheck(useService, useMethod, useInterAddress)) {
                run.computeIfAbsent(useService + "." + useMethod, (k) -> new AtomicInteger(0)).incrementAndGet();
            }
            totalCnt++;
        }

        long runCnt = run.values().stream().mapToInt(AtomicInteger::get).sum();
        System.out.println("runCnt/total:" + runCnt + "/" + totalCnt);
        return run;
    }

    @Test
    public void speedTest_service_01() throws Throwable {
        RuleParser ruleParser = getRuleParser();
        String speedBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/flow-control/speed-flow-service.xml"), "UTF-8");
        SpeedFlowControl rule = (SpeedFlowControl) ruleParser.ruleSettings(speedBody);
        List<InterAddress> doCallAddress = addressList();
        List<String> serviceIds = Arrays.asList("service_1", "service_2");
        List<String> serviceMethods = Arrays.asList("method_1", "method_2");

        long startTime = System.currentTimeMillis();
        Map<String, AtomicInteger> totalRun = doRunSpeedTest(startTime, rule, doCallAddress, serviceIds, serviceMethods);
        long costSec = (System.currentTimeMillis() - startTime) / 1000;

        Map<String, AtomicInteger> serviceRun = new HashMap<>();
        totalRun.forEach((key, cnt) -> {
            serviceRun.computeIfAbsent(key.split("\\.")[1], k -> new AtomicInteger(0)).getAndAdd(cnt.get());
        });

        long runAvg = (long) serviceRun.values().stream().mapToInt(AtomicInteger::get).average().orElse(0.0);
        long runTotal = serviceRun.values().stream().mapToInt(AtomicInteger::get).sum();
        long speed = (runAvg / costSec);

        System.out.println("call avg/total:" + runAvg + "/" + runTotal + ", Speed(s):" + speed + ", \tdetail:" + serviceRun);
        assert 19 <= speed && speed <= 21;//允许上下 1 的浮动
    }

    @Test
    public void speedTest_method_01() throws Throwable {
        RuleParser ruleParser = getRuleParser();
        String speedBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/flow-control/speed-flow-method.xml"), "UTF-8");
        SpeedFlowControl rule = (SpeedFlowControl) ruleParser.ruleSettings(speedBody);
        List<InterAddress> doCallAddress = addressList();
        List<String> serviceIds = Arrays.asList("service_1", "service_2");
        List<String> serviceMethods = Arrays.asList("method_1", "method_2");

        long startTime = System.currentTimeMillis();
        Map<String, AtomicInteger> totalRun = doRunSpeedTest(startTime, rule, doCallAddress, serviceIds, serviceMethods);
        long costSec = (System.currentTimeMillis() - startTime) / 1000;

        Map<String, AtomicInteger> methodRun = new HashMap<>();
        totalRun.forEach((key, cnt) -> {
            methodRun.computeIfAbsent(key, k -> new AtomicInteger(0)).getAndAdd(cnt.get());
        });

        long runAvg = (long) methodRun.values().stream().mapToInt(AtomicInteger::get).average().orElse(0.0);
        long runTotal = methodRun.values().stream().mapToInt(AtomicInteger::get).sum();
        long speed = (runAvg / costSec);

        System.out.println("call avg/total:" + runAvg + "/" + runTotal + ", Speed(s):" + speed + ", \tdetail:" + methodRun);
        assert 19 <= speed && speed <= 21;//允许上下 1 的浮动
    }

    @Test
    public void randomTest() throws Throwable {
        RuleParser ruleParser = getRuleParser();
        String randomBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/flow-control/flowcontrol-random.xml"), "UTF-8");
        RandomFlowControl rule = (RandomFlowControl) ruleParser.ruleSettings(randomBody);
        List<InterAddress> addressPool = addressList();

        Set<InterAddress> resultSet = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            InterAddress address = rule.getServiceAddress(addressPool);
            resultSet.add(address);
            System.out.println(address);
        }

        assert addressPool.size() == resultSet.size();
    }

    @Test
    public void unitTest() throws Throwable {
        RuleParser ruleParser = getRuleParser();
        String roomBody = IOUtils.readToString(ResourcesUtils.getResourceAsStream("/unit-flow.xml"), "UTF-8");
        UnitFlowControl rule = (UnitFlowControl) ruleParser.ruleSettings(roomBody);
        List<InterAddress> address = addressList();

        List<InterAddress> unit2 = rule.siftUnitAddress("etc2", address);
        System.out.println(unit2);
        assert unit2.size() == 2;

        List<InterAddress> unit3 = rule.siftUnitAddress("etc3", address);
        System.out.println(unit3);
        assert unit3.size() == 3;
    }
}
