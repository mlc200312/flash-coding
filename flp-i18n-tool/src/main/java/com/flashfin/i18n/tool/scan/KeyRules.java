package com.flashfin.i18n.tool.scan;

public final class KeyRules {

    public static final String ENUMERABLE = "com.kaffatech.mocha.common.lang.Enumerable";
    public static final String RESULT_CODE = "com.kaffatech.mocha.common.lang.ResultCode";
    public static final String STRING_ENUMERABLE = "com.kaffatech.mocha.common.annotation.StringEnumerable";

    private final String enumerableClassName;
    private final String resultCodeClassName;
    private final String stringEnumerableAnnotationName;

    private KeyRules(String enumerableClassName, String resultCodeClassName,
                     String stringEnumerableAnnotationName) {
        this.enumerableClassName = enumerableClassName;
        this.resultCodeClassName = resultCodeClassName;
        this.stringEnumerableAnnotationName = stringEnumerableAnnotationName;
    }

    public static KeyRules defaults() {
        return new KeyRules(ENUMERABLE, RESULT_CODE, STRING_ENUMERABLE);
    }

    public static KeyRules of(String enumerableClassName, String resultCodeClassName,
                              String stringEnumerableAnnotationName) {
        return new KeyRules(enumerableClassName, resultCodeClassName, stringEnumerableAnnotationName);
    }

    public String getEnumerableClassName() {
        return enumerableClassName;
    }

    public String getResultCodeClassName() {
        return resultCodeClassName;
    }

    public String getStringEnumerableAnnotationName() {
        return stringEnumerableAnnotationName;
    }
}
