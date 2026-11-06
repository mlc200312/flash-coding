package com.flashfin.i18n.tool.scan;

public final class ScanException extends Exception {

    public static final String MODULE_NOT_FOUND = "MODULE_NOT_FOUND";
    public static final String CLASSES_NOT_FOUND = "CLASSES_NOT_FOUND";
    public static final String CLASSPATH_BUILD_FAILED = "CLASSPATH_BUILD_FAILED";
    public static final String SCAN_FAILED = "SCAN_FAILED";
    public static final String FRAMEWORK_CLASS_MISSING = "FRAMEWORK_CLASS_MISSING";
    public static final String KEY_GENERATION_FAILED = "KEY_GENERATION_FAILED";

    private final String code;

    public ScanException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ScanException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
