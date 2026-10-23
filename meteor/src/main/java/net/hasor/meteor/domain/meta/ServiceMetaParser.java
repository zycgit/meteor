/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.util.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.type.TypeBindings;
import com.fasterxml.jackson.databind.type.TypeFactory;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.*;

/** 公共注解的唯一解释入口；类型声明与端点配置、协议契约分别管理。 */
public final class ServiceMetaParser {
    // ClassValue 只复用类的固定声明，不保存服务实例、挂载选项或外部契约资源。
    private static final ClassValue<ServiceMeta> SERVICES = new ClassValue<>() {
        @Override
        protected ServiceMeta computeValue(Class<?> type) {
            Map<Method, MethodMeta> methods = new LinkedHashMap<>();
            for (Method method : type.getMethods()) {
                boolean exposed = Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers());
                if (exposed && method.getDeclaringClass() != Object.class) {
                    methods.put(method, describeMethod(type, method));
                }
            }
            return new ServiceMeta(type, type.getAnnotation(MetService.class), //
                    type.isAnnotationPresent(MetMessage.class), methods);
        }
    };
    private static final ClassValue<TypeMeta>    TYPES    = new ClassValue<>() {
        @Override
        protected TypeMeta computeValue(Class<?> type) {
            return new TypeMeta(ResolvableType.forClass(type), type.getAnnotation(MetType.class));
        }
    };

    private ServiceMetaParser() {
    }

    public static ServiceMeta parse(Class<?> serviceType) {
        return SERVICES.get(serviceType);
    }

    static long serviceId(Class<?> type, MetService declaration) {
        // 当前接口的明确编号覆盖父接口；其他服务坐标仍沿用原声明语义。
        if (declaration != null && declaration.id() != -1L) {
            return declaration.id();
        }

        // 多父接口只能继承同一编号，避免由反射遍历顺序决定线上身份。
        long selected = -1L;
        for (Class<?> parent : type.getInterfaces()) {
            long inherited = parse(parent).getId();
            if (inherited == -1L) {
                continue;
            }
            if (selected != -1L && selected != inherited) {
                throw new IllegalArgumentException("Conflicting inherited service ids: " + type.getName());
            }
            selected = inherited;
        }
        return selected;
    }

    public static MethodMeta method(Class<?> owner, Method method) {
        MethodMeta description = parse(owner).getMethod(method);
        return description == null ? describeMethod(owner, method) : description;
    }

    public static MethodMeta method(Method method) {
        return method(method.getDeclaringClass(), method);
    }

    private static MethodMeta describeMethod(Class<?> owner, Method method) {
        return new MethodMeta(owner, method, method.getAnnotation(MetMethod.class), //
                method.getAnnotation(MetReturn.class), method.isAnnotationPresent(MetMessage.class), //
                method.isAnnotationPresent(MetIgnore.class));
    }

    static ParameterMeta parameter(Class<?> owner, Method method, int position) {
        Parameter parameter = method.getParameters()[position];
        ResolvableType type = methodType(owner, method, position);
        return new ParameterMeta(parameter, position, type, parameter.getAnnotation(MetParam.class));
    }

    static ResolvableType methodType(Class<?> owner, Method method, int position) {
        if (method.getTypeParameters().length == 0) {
            return position < 0 ? ResolvableType.forMethodReturnType(method, owner) //
                    : ResolvableType.forMethodParameter(method, position, owner);
        }
        // 方法变量遮蔽同名接口变量；未确定的变量保留其实际上界，不借用另一声明的绑定。
        TypeFactory factory = TypeFactory.defaultInstance();
        TypeBindings bindings = bindings(owner, method, factory);
        Type declared = position < 0 ? method.getGenericReturnType() : method.getGenericParameterTypes()[position];
        return resolvableType(factory.resolveMemberType(declared, bindings));
    }

    public static ParameterMeta parameter(Parameter parameter) {
        if (parameter.getDeclaringExecutable() instanceof Method method) {
            for (ParameterMeta description : method(method).getParameters()) {
                if (description.getJavaParameter().equals(parameter)) {
                    return description;
                }
            }
        }
        throw new IllegalArgumentException("Not an RPC method parameter: " + parameter);
    }

    public static TypeMeta type(Class<?> type) {
        return TYPES.get(type);
    }

    public static TypeMeta type(ResolvableType type) {
        if (type.getType() instanceof Class<?> raw && type.equals(ResolvableType.forClass(raw))) {
            return type(raw);
        }
        Class<?> raw = type.resolve(Object.class);
        return new TypeMeta(type, raw.getAnnotation(MetType.class));
    }

    public static EnumValueMeta enumValue(Enum<?> value) {
        return type(value.getDeclaringClass()).getEnumValues().get(value.name());
    }

    static Map<String, EnumValueMeta> enumeration(Class<?> type) {
        Map<String, EnumValueMeta> result = new LinkedHashMap<>();
        if (!type.isEnum()) {
            return Map.of();
        }
        for (Object value : type.getEnumConstants()) {
            Enum<?> constant = (Enum<?>) value;
            try {
                MetEnumValue declaration = type.getField(constant.name()).getAnnotation(MetEnumValue.class);
                result.put(constant.name(), new EnumValueMeta(constant, declaration));
            } catch (NoSuchFieldException failure) {
                throw new IllegalArgumentException("Cannot inspect enum: " + type.getName(), failure);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, PropertyMeta> properties(ResolvableType owner) {
        Class<?> raw = owner.resolve(Object.class);
        if (raw.isPrimitive() || raw.isEnum() || raw.isArray() || raw.getName().startsWith("java.")) {
            return Map.of();
        }
        // Java 字段和访问方法保留在同一个逻辑属性中，不在这里要求 DTO 可写或创建实例。
        Map<String, PropertyDescriptor> beans = new LinkedHashMap<>();
        try {
            for (PropertyDescriptor bean : Introspector.getBeanInfo(raw).getPropertyDescriptors()) {
                if (!StringUtils.equals(bean.getName(), "class")) {
                    beans.put(bean.getName(), bean);
                }
            }
        } catch (IntrospectionException failure) {
            throw new IllegalArgumentException("Cannot inspect DTO: " + raw.getName(), failure);
        }
        Map<String, PropertyMeta> result = new LinkedHashMap<>();
        for (Class<?> type = raw; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                boolean excluded = Modifier.isStatic(field.getModifiers()) || field.isSynthetic();
                boolean shadowed = result.containsKey(field.getName());
                if (excluded || shadowed) {
                    continue;
                }
                PropertyDescriptor bean = beans.remove(field.getName());
                Method getter = bean == null ? null : bean.getReadMethod();
                Method setter = bean == null ? null : bean.getWriteMethod();
                ResolvableType propertyType = ResolvableType.forField(field, owner);
                result.put(field.getName(), property(field.getName(), propertyType, field, getter, setter));
            }
        }
        for (PropertyDescriptor bean : beans.values()) {
            Method getter = bean.getReadMethod();
            Method setter = bean.getWriteMethod();
            Method member = getter == null ? setter : getter;
            ResolvableType declaring = owner.as(member.getDeclaringClass());
            ResolvableType propertyType = getter == null //
                    ? ResolvableType.forType(setter.getGenericParameterTypes()[0], declaring) //
                    : ResolvableType.forType(getter.getGenericReturnType(), declaring);
            result.put(bean.getName(), property(bean.getName(), propertyType, getter, setter));
        }
        return Collections.unmodifiableMap(result);
    }

    public static PropertyMeta property(String javaName, ResolvableType type, AnnotatedElement... members) {
        List<AnnotatedElement> actual = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (AnnotatedElement member : members) {
            if (member != null) {
                actual.add(member);
                MetField field = member.getAnnotation(MetField.class);
                if (field != null) {
                    names.add(field.name());
                    ids.add(field.id());
                }
            }
        }
        return property(javaName, type, actual, names, ids);
    }

    public static boolean isProperty(Annotated member) {
        return member.hasAnnotation(MetField.class);
    }

    /** Jackson 的有效成员由当前 mapper 提供，共同注解的解释与合并仍只在公共层实现。 */
    public static PropertyMeta property(BeanPropertyDefinition property) {
        return property(property, null);
    }

    public static PropertyMeta property(BeanPropertyDefinition output, BeanPropertyDefinition input) {
        Set<AnnotatedMember> selected = new LinkedHashSet<>();
        for (BeanPropertyDefinition property : new BeanPropertyDefinition[] { output, input }) {
            if (property != null) {
                Collections.addAll(selected, property.getField(), property.getGetter(), //
                        property.getSetter(), property.getConstructorParameter());
            }
        }
        selected.remove(null);
        BeanPropertyDefinition property = output == null ? input : output;
        return property(property.getInternalName(), property.getPrimaryType(), new ArrayList<>(selected));
    }

    public static PropertyMeta property(String javaName, JavaType type, List<AnnotatedMember> selected) {
        List<AnnotatedElement> members = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (AnnotatedMember member : selected) {
            AnnotatedElement actual = (AnnotatedElement) member.getMember();
            members.add(actual);
            MetField raw = actual.getAnnotation(MetField.class);
            MetField effective = member.getAnnotation(MetField.class);
            // 原成员和 mixin 可分别补充名称与编号，显式原成员声明优先。
            if (raw != null || effective != null) {
                int id = raw == null ? 0 : raw.id();
                String name = raw == null ? "" : raw.name();
                if (effective != null) {
                    if (id == 0) {
                        id = effective.id();
                    }
                    if (name.isEmpty()) {
                        name = effective.name();
                    }
                }
                ids.add(id);
                names.add(name);
            }
        }
        return property(javaName, resolvableType(type), members, names, ids);
    }

    private static PropertyMeta property(String javaName, ResolvableType type, //
            List<AnnotatedElement> members, List<String> names, List<Integer> ids) {
        String name = "";
        int id = 0;
        for (int index = 0; index < names.size(); index++) {
            String next = names.get(index);
            if (!next.isEmpty()) {
                if (StringUtils.isBlank(next)) {
                    throw new IllegalArgumentException("Protocol property name must not be blank: " + javaName);
                }
                if (!name.isEmpty() && !StringUtils.equals(name, next)) {
                    throw new IllegalArgumentException("Conflicting property names: " + javaName);
                }
                name = next;
            }
            int nextId = ids.get(index);
            if (nextId != 0) {
                if (id != 0 && id != nextId) {
                    throw new IllegalArgumentException("Conflicting property ids: " + javaName);
                }
                id = nextId;
            }
        }
        return new PropertyMeta(javaName, type, members, name, id, !names.isEmpty());
    }

    /** 当前 mapper 只提供类型工厂，继承泛型和方法变量遮蔽规则由公共层解释。 */
    public static JavaType[] parameterTypes(MethodMeta description, TypeFactory factory) {
        Method method = description.getJavaMethod();
        TypeBindings bindings = bindings(description, factory);
        Type[] parameters = method.getGenericParameterTypes();
        JavaType[] result = new JavaType[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            result[index] = factory.resolveMemberType(parameters[index], bindings);
        }
        return result;
    }

    public static JavaType returnType(MethodMeta description, TypeFactory factory) {
        TypeBindings bindings = bindings(description, factory);
        return factory.resolveMemberType(description.getJavaMethod().getGenericReturnType(), bindings);
    }

    private static TypeBindings bindings(MethodMeta description, TypeFactory factory) {
        return bindings(description.getServiceType(), description.getJavaMethod(), factory);
    }

    private static TypeBindings bindings(Class<?> serviceType, Method method, TypeFactory factory) {
        JavaType owner = factory.constructType(serviceType).findSuperType(method.getDeclaringClass());
        if (owner == null) {
            throw new IllegalArgumentException("Method does not belong to service: " + method);
        }
        return methodBindings(owner.getBindings(), method);
    }

    private static TypeBindings methodBindings(TypeBindings owner, Method method) {
        TypeVariable<Method>[] variables = method.getTypeParameters();
        if (variables.length == 0 || owner.isEmpty()) {
            return owner;
        }
        Set<String> methodNames = new HashSet<>();
        for (TypeVariable<?> variable : variables) {
            methodNames.add(variable.getName());
        }

        // Jackson 按变量名称查找绑定；方法的 <T> 必须遮蔽服务接口的同名 T。
        // 保留其他接口变量，让 <R extends T> 仍可解析到服务声明的 T 上界。
        // 名称与类型同步逐项添加，相同下标必须对应同一个接口变量的绑定。
        List<String> names = new ArrayList<>();
        List<JavaType> types = new ArrayList<>();
        for (int index = 0; index < owner.size(); index++) {
            String name = owner.getBoundName(index);
            if (!methodNames.contains(name)) {
                names.add(name);
                types.add(owner.getBoundType(index));
            }
        }
        return TypeBindings.create(names, types);
    }

    /** 将已解析的类型参数传回使用 Cobble 泛型模型的协议；不附加协议类型转换。 */
    public static ResolvableType resolvableType(JavaType type) {
        if (type.isArrayType()) {
            return ResolvableType.forArrayComponent(resolvableType(type.getContentType()));
        }
        int count = type.containedTypeCount();
        if (count == 0) {
            return ResolvableType.forClass(type.getRawClass());
        }
        ResolvableType[] arguments = new ResolvableType[count];
        for (int index = 0; index < count; index++) {
            arguments[index] = resolvableType(type.containedTypeOrUnknown(index));
        }
        return ResolvableType.forClassWithGenerics(type.getRawClass(), arguments);
    }
}
