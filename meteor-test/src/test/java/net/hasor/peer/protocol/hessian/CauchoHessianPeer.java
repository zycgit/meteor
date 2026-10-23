/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.hessian;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import com.caucho.hessian.client.HessianProxyFactory;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianInputFactory;
import com.caucho.hessian.io.HessianOutput;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.example.protocol.hessian.HessianInteropDemoImpl;
import net.hasor.example.protocol.hessian.HessianInteropPerson;

/** 官方 Caucho 进程；只依赖 JDK、Caucho 和普通业务契约。 */
public final class CauchoHessianPeer {
    private CauchoHessianPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        // 即使标准测试输出中有其他测试类，运行依赖也不能包含 Meteor 内核。
        requireIsolatedClasspath();
        String mode = arguments[0];
        switch (mode) {
            case "server": {
                try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl())) {
                    System.out.println("READY " + server.getPort());
                    System.out.flush();
                    while (System.in.read() != -1) {
                        // 管理进程关闭 stdin 时，本进程关闭自己拥有的监听器和线程池。
                    }
                }
                return;
            }
            default: {
                break;
            }
        }

        // 序列化文件仅作为本地进程控制通道，真正的 RPC 由官方 SDK 通过网络完成。
        Path request = Path.of(arguments[1]);
        Path response = Path.of(arguments[2]);
        Object[] input;
        try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(request))) {
            input = (Object[]) stream.readObject();
        }
        Object value = null;
        Throwable failure = null;
        try {
            value = execute(mode, input);
        } catch (InvocationTargetException invocation) {
            failure = invocation.getCause();
        } catch (Throwable exception) {
            failure = exception;
        }
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(response))) {
            stream.writeObject(new Object[] { value, failure });
        }
        System.out.println("RESULT completed");
    }

    private static Object execute(String mode, Object[] input) throws Throwable {
        // 各模式保持独立协议操作，不将业务场景或断言隐藏在运行器中。
        switch (mode) {
            case "client": {
                int version = (Integer) input[0];
                String address = (String) input[1];
                String name = (String) input[2];
                Class<?>[] types = (Class<?>[]) input[3];
                Object[] values = (Object[]) input[4];
                HessianProxyFactory factory = new HessianProxyFactory();
                factory.setHessian2Request(version == 2);
                factory.setHessian2Reply(true);
                factory.setOverloadEnabled(true);
                factory.setConnectTimeout(5000);
                factory.setReadTimeout(10000);
                Object proxy = factory.create(HessianInteropDemo.class, address);
                Method method = HessianInteropDemo.class.getMethod(name, types);
                return method.invoke(proxy, values);
            }
            case "strict-hessian1": {
                return strictHessian1((String) input[0], (HessianInteropPerson) input[1]);
            }
            case "encode-hessian2": {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Hessian2Output output = new Hessian2Output(bytes);
                output.call("echo", new Object[] { input[0] });
                output.close();
                return bytes.toByteArray();
            }
            case "decode-hessian2": {
                ByteArrayInputStream bytes = new ByteArrayInputStream((byte[]) input[0]);
                HessianInputFactory.HeaderType header = new HessianInputFactory().readHeader(bytes);
                if (header != HessianInputFactory.HeaderType.HESSIAN_2) {
                    throw new AssertionError("Expected Hessian 2 response header, got " + header);
                }
                Hessian2Input reader = new Hessian2Input(bytes);
                try {
                    return reader.readReply(String.class);
                } finally {
                    reader.close();
                }
            }
            default: {
                throw new IllegalArgumentException("Unknown Caucho peer mode: " + mode);
            }
        }
    }

    private static Object[] strictHessian1(String address, HessianInteropPerson value) throws Throwable {
        // 使用官方完整 H1 writer，HTTP 客户端不参与 Hessian 编码。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.setVersion(1);
        output.call("person", new Object[] { value });
        output.flush();
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)) //
                .timeout(Duration.ofSeconds(10)) //
                .header("Content-Type", "x-application/hessian") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        // 官方 reader 还原结果，原始 HTTP 状态与线协议版本一起返回给场景断言。
        byte[] body = response.body();
        HessianInput reader = new HessianInput(new ByteArrayInputStream(body));
        Object result = reader.readReply(HessianInteropPerson.class);
        return new Object[] { response.statusCode(), body[0], body[1], result };
    }

    private static void requireIsolatedClasspath() throws ClassNotFoundException {
        try {
            Class.forName("net.hasor.meteor.MetContext", false, CauchoHessianPeer.class.getClassLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("Official Caucho peer must not load Meteor runtime");
    }
}
