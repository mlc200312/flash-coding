package com.flashfin.i18n.tool.properties;

public final class PropertiesFileException extends Exception {

    public static final String READ_FAILED = "PROPERTIES_READ_FAILED";
    public static final String WRITE_FAILED = "PROPERTIES_WRITE_FAILED";
    public static final String VERIFY_FAILED = "PROPERTIES_VERIFY_FAILED";

    private final String code;

    public PropertiesFileException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
