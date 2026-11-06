package com.flashfin.i18n.tool.scan;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public final class KeyGenerator {

    private final KeyRules rules;

    public KeyGenerator(KeyRules rules) {
        this.rules = rules;
    }

    public static KeyGenerator defaults() {
        return new KeyGenerator(KeyRules.defaults());
    }

    public GeneratedKeys generate(List<Class<?>> classes, ClassLoader loader) throws ScanException {
        Class<?> enumerableType = loadRequired(rules.getEnumerableClassName(), loader);
        Class<?> resultCodeType = loadRequired(rules.getResultCodeClassName(), loader);
        Method enumerableGetCode = method(enumerableType, "getCode");
        Method enumerableGetCodeString = method(enumerableType, "getCodeString");
        Method resultCodeGetCode = method(resultCodeType, "getCode");

        GeneratedKeys keys = new GeneratedKeys();
        for (Class<?> clazz : classes) {
            if (!clazz.isEnum()) {
                continue;
            }
            Object[] constants = clazz.getEnumConstants();
            if (constants == null) {
                continue;
            }
            boolean stringMode = hasAnnotation(clazz, rules.getStringEnumerableAnnotationName());
            String simpleName = clazz.getSimpleName();
            for (Object constant : constants) {
                if (constant == null) {
                    continue;
                }
                if (resultCodeType.isInstance(constant)) {
                    Object code = invoke(resultCodeGetCode, constant, clazz);
                    keys.addCodeKey("code_" + String.valueOf(code), clazz.getName());
                }
                if (enumerableType.isInstance(constant)) {
                    Object code = stringMode
                            ? invoke(enumerableGetCodeString, constant, clazz)
                            : invoke(enumerableGetCode, constant, clazz);
                    keys.addEnumKey(simpleName + "_" + String.valueOf(code), clazz.getName());
                }
            }
        }
        return keys;
    }

    private Class<?> loadRequired(String className, ClassLoader loader) throws ScanException {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new ScanException(ScanException.FRAMEWORK_CLASS_MISSING,
                    "扫描类路径中缺少框架类型: " + className, e);
        }
    }

    private Method method(Class<?> type, String name) throws ScanException {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new ScanException(ScanException.FRAMEWORK_CLASS_MISSING,
                    "框架类型 " + type.getName() + " 缺少方法: " + name, e);
        }
    }

    private boolean hasAnnotation(Class<?> clazz, String annotationName) {
        if (annotationName == null || annotationName.isEmpty()) {
            return false;
        }
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private Object invoke(Method method, Object target, Class<?> owner) throws ScanException {
        try {
            return method.invoke(target);
        } catch (IllegalAccessException e) {
            throw new ScanException(ScanException.KEY_GENERATION_FAILED,
                    "无法调用 " + owner.getName() + "#" + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ScanException(ScanException.KEY_GENERATION_FAILED,
                    "调用 " + owner.getName() + "#" + method.getName() + " 失败: " + cause, cause);
        }
    }
}
