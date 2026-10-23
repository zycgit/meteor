/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.SerializerFactory;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.dataformat.xml.ser.XmlBeanSerializer;

/** 保留用户的 Jackson 工厂与属性配置，仅将内置 XML bean 输出器适配为 token 输出器。 */
final class SoapJacksonSerializerFactory extends SerializerFactory {
    private final SerializerFactory delegate;

    SoapJacksonSerializerFactory(SerializerFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public SerializerFactory withAdditionalSerializers(Serializers additional) {
        return new SoapJacksonSerializerFactory(this.delegate.withAdditionalSerializers(additional));
    }

    @Override
    public SerializerFactory withAdditionalKeySerializers(Serializers additional) {
        return new SoapJacksonSerializerFactory(this.delegate.withAdditionalKeySerializers(additional));
    }

    @Override
    public SerializerFactory withSerializerModifier(BeanSerializerModifier modifier) {
        return new SoapJacksonSerializerFactory(this.delegate.withSerializerModifier(modifier));
    }

    @Override
    public JsonSerializer<Object> createSerializer(SerializerProvider provider, JavaType type) //
            throws JsonMappingException {
        JsonSerializer<Object> serializer = this.delegate.createSerializer(provider, type);
        // XmlBeanSerializer 的多态写出依赖 ToXmlGenerator；属性绑定及自定义处理器仍沿用原实例的配置。
        if (serializer != null && serializer.getClass() == XmlBeanSerializer.class) {
            return new TokenBeanSerializer((BeanSerializerBase) serializer);
        }
        return serializer;
    }

    @Override
    public TypeSerializer createTypeSerializer(SerializationConfig config, JavaType type) throws JsonMappingException {
        return this.delegate.createTypeSerializer(config, type);
    }

    @Override
    public JsonSerializer<Object> createKeySerializer(SerializerProvider provider, JavaType type, //
            JsonSerializer<Object> defaultImpl) throws JsonMappingException {
        return this.delegate.createKeySerializer(provider, type, defaultImpl);
    }

    @Override
    @SuppressWarnings("deprecation")
    public JsonSerializer<Object> createKeySerializer(SerializationConfig config, JavaType type, //
            JsonSerializer<Object> defaultImpl) throws JsonMappingException {
        return this.delegate.createKeySerializer(config, type, defaultImpl);
    }

    private static final class TokenBeanSerializer extends BeanSerializer {
        private TokenBeanSerializer(BeanSerializerBase source) {
            super(source);
        }
    }
}
