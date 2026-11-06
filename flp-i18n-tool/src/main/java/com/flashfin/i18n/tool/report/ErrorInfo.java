package com.flashfin.i18n.tool.report;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"code", "message", "hint", "retryable"})
public final class ErrorInfo {

    private final String code;
    private final String message;
    private final String hint;
    private final boolean retryable;

    public ErrorInfo(String code, String message, String hint, boolean retryable) {
        this.code = code;
        this.message = message;
        this.hint = hint;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getHint() {
        return hint;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
