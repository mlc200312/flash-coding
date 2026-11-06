package com.flashfin.i18n.tool.platform;

public final class PlatformSaveResult {

    private final boolean ok;
    private final int status;
    private final String code;
    private final String message;
    private final boolean retryable;

    private PlatformSaveResult(boolean ok, int status, String code, String message,
                               boolean retryable) {
        this.ok = ok;
        this.status = status;
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public static PlatformSaveResult success(int status) {
        return new PlatformSaveResult(true, status, null, null, false);
    }

    public static PlatformSaveResult failure(int status, String code, String message,
                                             boolean retryable) {
        return new PlatformSaveResult(false, status, code, message, retryable);
    }

    public boolean isOk() {
        return ok;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
