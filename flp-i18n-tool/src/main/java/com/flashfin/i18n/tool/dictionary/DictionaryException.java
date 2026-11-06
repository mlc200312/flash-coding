package com.flashfin.i18n.tool.dictionary;

public final class DictionaryException extends Exception {

    public static final String INVALID_LANGUAGE = "INVALID_LANGUAGE";
    public static final String NOT_FOUND = "DICTIONARY_NOT_FOUND";
    public static final String FETCH_FAILED = "DICTIONARY_FETCH_FAILED";
    public static final String RESPONSE_INVALID = "DICTIONARY_RESPONSE_INVALID";
    public static final String RESPONSE_TOO_LARGE = "DICTIONARY_RESPONSE_TOO_LARGE";

    private final String code;
    private final boolean retryable;

    public DictionaryException(String code, String message) {
        this(code, message, false, null);
    }

    public DictionaryException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
