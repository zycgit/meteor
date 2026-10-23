/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.generated.*;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;

/** 在独立 JVM 中运行官方生成的 Client、Processor 和服务实现。 */
public final class ThriftPeer {
    private ThriftPeer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> arguments = arguments(args);
        switch (arguments.getOrDefault("command", "server")) {
            case "server":
                // 官方 Processor 持有原生服务，multiplexed 只改变官方协议中的服务名前缀。
                TProcessor processor = new ExtendedInteropService.Processor<>(new ThriftPeerService());
                if (Boolean.parseBoolean(arguments.getOrDefault("multiplexed", "false"))) {
                    TMultiplexedProcessor multiplexed = new TMultiplexedProcessor();
                    String name = arguments.getOrDefault("service-name", "InteropService");
                    multiplexed.registerProcessor(name, processor);
                    processor = multiplexed;
                }
                ThriftPeerTransport.server(arguments, processor);
                break;
            case "client":
                // 一次进程只执行一个明确场景，所有 RPC 都经官方客户端和真实网络。
                try (TTransport transport = ThriftPeerTransport.client(arguments)) {
                    TProtocol protocol = ThriftPeerTransport.protocolFactory(arguments).getProtocol(transport);
                    if (Boolean.parseBoolean(arguments.getOrDefault("multiplexed", "false"))) {
                        String name = arguments.getOrDefault("service-name", "InteropService");
                        protocol = new TMultiplexedProtocol(protocol, name);
                    }
                    ExtendedInteropService.Client client = new ExtendedInteropService.Client(protocol);
                    String result = invoke(client, protocol, arguments);
                    System.out.println("RESULT " + result);
                    System.out.flush();
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown peer command");
        }
    }

    private static String invoke(ExtendedInteropService.Client client, TProtocol protocol, //
            Map<String, String> arguments) throws Exception {
        String value = arguments.getOrDefault("value", "hello");
        String scenario = arguments.getOrDefault("scenario", "echo");
        switch (scenario) {
            case "echo":
                return client.echo(value);
            case "add":
                int left = Integer.parseInt(arguments.getOrDefault("left", "19"));
                int right = Integer.parseInt(arguments.getOrDefault("right", "23"));
                return Integer.toString(client.add(left, right));
            case "dto":
                Sample expected = sample();
                check(expected.equals(client.mirror(expected)), "DTO fields changed");
                return "dto-ok";
            case "optional":
                // 未设置的 optional 字段不能在往返后变成已设置的空值或零值。
                Sample optional = client.mirror(new Sample());
                check(!optional.isSetNote(), "Optional note was set");
                check(!optional.isSetOptionalCount(), "Optional count was set");
                check(!optional.isSetChoice(), "Optional union was set");
                check(!optional.isSetRequestId(), "Optional UUID was set");
                return "optional-ok";
            case "defaults":
                Sample defaults = client.defaults();
                check(defaults.getDefaultCount() == 17, "Integer default changed");
                check(defaults.getShade() == Shade.BLUE, "Enum default changed");
                return "defaults-ok";
            case "list":
                List<String> list = Arrays.asList("alpha", "汉字", "");
                check(list.equals(client.echoList(list)), "List changed");
                return "list-ok";
            case "map":
                Map<String, Integer> map = new LinkedHashMap<>();
                map.put("alpha", 17);
                map.put("汉字", -3);
                check(map.equals(client.echoMap(map)), "Map changed");
                return "map-ok";
            case "set":
                Set<Long> set = new LinkedHashSet<>(Arrays.asList(7L, -9L, Long.MAX_VALUE));
                check(set.equals(client.echoSet(set)), "Set changed");
                return "set-ok";
            case "binary":
                ByteBuffer binary = ByteBuffer.wrap(new byte[] { 0, 1, 2, 127, -128, -1 });
                check(binary.equals(client.echoBinary(binary)), "Binary changed");
                return "binary-ok";
            case "enum":
                check(client.echoEnum(Shade.RED) == Shade.RED, "Enum changed");
                return "enum-ok";
            case "union":
                Choice union = Choice.count(Long.MAX_VALUE);
                check(union.equals(client.echoUnion(union)), "Union changed");
                return "union-ok";
            case "uuid":
                UUID uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
                check(uuid.equals(client.echoUuid(uuid)), "UUID changed");
                return "uuid-ok";
            case "void":
                client.ping();
                return "void-ok";
            case "declared":
                try {
                    client.fail(value);
                    throw new IllegalStateException("Declared failure did not reach the official client");
                } catch (DeclaredFailure expectedFailure) {
                    return "declared:" + expectedFailure.getCode() + ":" + expectedFailure.getReason();
                }
            case "unexpected":
                try {
                    client.crash(value);
                    throw new IllegalStateException("Unexpected failure did not reach the official client");
                } catch (TApplicationException applicationFailure) {
                    return "application:" + applicationFailure.getType();
                }
            case "oneway":
                return notifyAndObserve(client, value);
            case "extended":
                return client.extended(value);
            case "bad-method":
                return unknownMethod(protocol);
            case "bad-mux":
                TProtocol raw = ThriftPeerTransport.protocolFactory(arguments).getProtocol(protocol.getTransport());
                return unknownMultiplexedService(raw);
            default:
                throw new IllegalArgumentException("Unknown peer scenario: " + scenario);
        }
    }

    private static String notifyAndObserve(InteropService.Iface client, String value) throws Exception {
        // 前后计数验证通知真正到达服务，并以可观察条件等待异步消费。
        int before = client.notificationCount();
        client.notify(value);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (client.notificationCount() > before) {
                return "oneway-ok";
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new IllegalStateException("Oneway notification did not reach the service");
    }

    private static String unknownMethod(TProtocol protocol) throws Exception {
        // 负向报文仍由官方协议和官方生成的空参数结构写入。
        protocol.writeMessageBegin(new TMessage("missingMethod", TMessageType.CALL, 1));
        new InteropService.ping_args().write(protocol);
        protocol.writeMessageEnd();
        protocol.getTransport().flush();

        TMessage reply = protocol.readMessageBegin();
        check(reply.type == TMessageType.EXCEPTION, "Unknown method did not return an application exception");
        TApplicationException failure = TApplicationException.readFrom(protocol);
        protocol.readMessageEnd();
        return "application:" + failure.getType();
    }

    private static String unknownMultiplexedService(TProtocol protocol) throws Exception {
        // 官方 TMultiplexedProcessor 对未知服务可能关闭连接；保留对端实际拒绝类型。
        TProtocol missing = new TMultiplexedProtocol(protocol, "MissingService");
        try {
            new InteropService.Client(missing).echo("missing");
            throw new IllegalStateException("Unknown multiplexed service was accepted");
        } catch (TException rejected) {
            return "rejected:" + rejected.getClass().getSimpleName();
        }
    }

    private static Sample sample() {
        // 覆盖每种标量、嵌套容器和有值的 optional 字段，避免只验证字符串 DTO。
        Sample value = new Sample();
        value.setId(5_000_000_007L);
        value.setText("Ada 汉字");
        value.setEnabled(true);
        value.setSmall((short) -1234);
        value.setCount(-123_456);
        value.setRatio(42.5);
        value.setPayload(new byte[] { 0, 1, 127, -128, -1 });
        value.setTags(Arrays.asList("alpha", "汉字"));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("language", "中文");
        value.setLabels(labels);
        value.setCodes(new LinkedHashSet<>(Arrays.asList(7, -9)));
        value.setTiny((byte) -17);
        value.setNote("present");
        value.setOptionalCount(0);
        value.setDefaultCount(29);
        value.setShade(Shade.RED);
        value.setChoice(Choice.text("selected"));
        value.setRequestId(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        return value;
    }

    private static Map<String, String> arguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Expected --name value or --name=value");
            }
            int separator = argument.indexOf('=');
            if (separator > 2) {
                result.put(argument.substring(2, separator), argument.substring(separator + 1));
            } else {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + argument);
                }
                result.put(argument.substring(2), args[++index]);
            }
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
