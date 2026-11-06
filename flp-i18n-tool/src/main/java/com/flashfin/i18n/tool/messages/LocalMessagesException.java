package com.flashfin.i18n.tool.messages;

public final class LocalMessagesException extends Exception {

    public static final String LOCAL_MESSAGES_READ_FAILED = "LOCAL_MESSAGES_READ_FAILED";

    private final String code;

    public LocalMessagesException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
