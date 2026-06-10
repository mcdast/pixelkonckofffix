package com.pixelknockoff.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 反射工具类
 * 提供安全的反射操作，用于访问 Pixelmon 模组内部方法
 */
public class ReflectionUtil {

    private static final Map<String, Class<?>> CLASS_CACHE = new HashMap<String, Class<?>>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<String, Method>();
    private static final Map<String, Field> FIELD_CACHE = new HashMap<String, Field>();

    /**
     * 清除缓存
     */
    public static void clearCache() {
        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
    }

    /**
     * 获取类对象 (带缓存)
     * @param className 类全名
     * @return Class对象
     * @throws ClassNotFoundException 如果类不存在
     */
    public static Class<?> getClass(String className) throws ClassNotFoundException {
        if (CLASS_CACHE.containsKey(className)) {
            return CLASS_CACHE.get(className);
        }
        Class<?> clazz = Class.forName(className);
        CLASS_CACHE.put(className, clazz);
        return clazz;
    }

    /**
     * 获取方法对象 (带缓存)
     * @param className 类全名
     * @param methodName 方法名
     * @param paramTypes 参数类型
     * @return Method对象
     * @throws ClassNotFoundException 如果类不存在
     * @throws NoSuchMethodException 如果方法不存在
     */
    public static Method getMethod(String className, String methodName, Class<?>... paramTypes)
            throws ClassNotFoundException, NoSuchMethodException {
        String key = className + "." + methodName + "(" + arrayToString(paramTypes) + ")";
        if (METHOD_CACHE.containsKey(key)) {
            return METHOD_CACHE.get(key);
        }
        Class<?> clazz = getClass(className);
        Method method = clazz.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        METHOD_CACHE.put(key, method);
        return method;
    }

    /**
     * 获取方法对象 (不带缓存)
     * @param clazz 类对象
     * @param methodName 方法名
     * @param paramTypes 参数类型
     * @return Method对象
     * @throws NoSuchMethodException 如果方法不存在
     */
    public static Method getMethod(Class<?> clazz, String methodName, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method method = clazz.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method;
    }

    /**
     * 获取字段对象 (带缓存)
     * @param className 类全名
     * @param fieldName 字段名
     * @return Field对象
     * @throws ClassNotFoundException 如果类不存在
     * @throws NoSuchFieldException 如果字段不存在
     */
    public static Field getField(String className, String fieldName)
            throws ClassNotFoundException, NoSuchFieldException {
        String key = className + "." + fieldName;
        if (FIELD_CACHE.containsKey(key)) {
            return FIELD_CACHE.get(key);
        }
        Class<?> clazz = getClass(className);
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        FIELD_CACHE.put(key, field);
        return field;
    }

    /**
     * 获取字段对象 (不带缓存)
     * @param clazz 类对象
     * @param fieldName 字段名
     * @return Field对象
     * @throws NoSuchFieldException 如果字段不存在
     */
    public static Field getField(Class<?> clazz, String fieldName)
            throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    /**
     * 调用实例方法
     * @param method 方法对象
     * @param instance 实例对象
     * @param args 参数
     * @return 方法返回值
     * @throws Exception 如果调用失败
     */
    public static Object invokeMethod(Method method, Object instance, Object... args)
            throws Exception {
        return method.invoke(instance, args);
    }

    /**
     * 调用静态方法
     * @param method 方法对象
     * @param args 参数
     * @return 方法返回值
     * @throws Exception 如果调用失败
     */
    public static Object invokeStaticMethod(Method method, Object... args)
            throws Exception {
        return method.invoke(null, args);
    }

    /**
     * 获取字段值
     * @param field 字段对象
     * @param instance 实例对象
     * @return 字段值
     * @throws Exception 如果获取失败
     */
    public static Object getFieldValue(Field field, Object instance)
            throws Exception {
        return field.get(instance);
    }

    /**
     * 设置字段值
     * @param field 字段对象
     * @param instance 实例对象
     * @param value 新值
     * @throws Exception 如果设置失败
     */
    public static void setFieldValue(Field field, Object instance, Object value)
            throws Exception {
        field.set(instance, value);
    }

    /**
     * 检查类是否存在
     * @param className 类全名
     * @return true如果类存在
     */
    public static boolean classExists(String className) {
        try {
            getClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 检查方法是否存在
     * @param className 类全名
     * @param methodName 方法名
     * @param paramTypes 参数类型
     * @return true如果方法存在
     */
    public static boolean methodExists(String className, String methodName, Class<?>... paramTypes) {
        try {
            getMethod(className, methodName, paramTypes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取类的所有字段 (包括父类)
     * @param clazz 类对象
     * @return 字段数组
     */
    public static Field[] getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<Field>();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                fields.add(field);
            }
            clazz = clazz.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    /**
     * 获取类的所有方法 (包括父类)
     * @param clazz 类对象
     * @return 方法数组
     */
    public static Method[] getAllMethods(Class<?> clazz) {
        java.util.List<Method> methods = new java.util.ArrayList<Method>();
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                methods.add(method);
            }
            clazz = clazz.getSuperclass();
        }
        return methods.toArray(new Method[0]);
    }

    private static String arrayToString(Class<?>[] arr) {
        if (arr == null || arr.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(arr[i].getName());
        }
        return sb.toString();
    }
}
