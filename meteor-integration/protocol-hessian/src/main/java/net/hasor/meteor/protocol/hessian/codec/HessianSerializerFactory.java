/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import com.caucho.burlap.io.BurlapRemoteObject;
import com.caucho.hessian.io.*;

/** 保留原生对象图编码，阻止可调用远程对象进入 RPC 参数与返回值。 */
final class HessianSerializerFactory extends SerializerFactory {
    HessianSerializerFactory(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public Serializer getSerializer(Class type) throws HessianProtocolException {
        rejectRemote(type);
        return super.getSerializer(type);
    }

    @Override
    public Deserializer getDeserializer(Class type) throws HessianProtocolException {
        rejectRemote(type);
        return super.getDeserializer(type);
    }

    @Override
    public Deserializer getDeserializer(String type) throws HessianProtocolException {
        // 原生工厂递归解析数组类型；先限制维数，避免短报文触发深层递归。
        if (type != null) {
            int dimensions = 0;
            while (dimensions < type.length() && type.charAt(dimensions) == '[') {
                dimensions++;
            }
            if (dimensions > 64) {
                throw new HessianProtocolException("Hessian array type exceeds maximum depth");
            }
        }

        // 在原生工厂的容错分支之前检查，避免被拒绝的类型退化成普通 Map。
        if (type != null && !type.isEmpty() && !type.startsWith("[")) {
            try {
                rejectRemote(this.loadSerializedClass(type));
            } catch (ClassNotFoundException ignored) {
                // 原生工厂继续处理内置短类型名及接收端没有定义的普通业务类型。
            }
        }
        return super.getDeserializer(type);
    }

    private static void rejectRemote(Class<?> type) throws HessianProtocolException {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        boolean hessianRemote = HessianRemote.class.isAssignableFrom(type);
        boolean remoteObject = HessianRemoteObject.class.isAssignableFrom(type);
        boolean burlapRemote = BurlapRemoteObject.class.isAssignableFrom(type);
        if (hessianRemote || remoteObject || burlapRemote) {
            throw new HessianProtocolException("Callable remote objects are not supported");
        }
    }
}
