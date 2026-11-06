package com.flashfin.i18n.tool.config;

public final class ConfigException extends Exception {

    public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
    public static final String CONFIG_PARSE_ERROR = "CONFIG_PARSE_ERROR";
    public static final String CONFIG_INVALID = "CONFIG_INVALID";

    private final String code;

    public ConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
