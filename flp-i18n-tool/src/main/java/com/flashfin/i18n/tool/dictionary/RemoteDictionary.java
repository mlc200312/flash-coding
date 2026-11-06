package com.flashfin.i18n.tool.dictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RemoteDictionary {

    private final String language;
    private final String url;
    private final Map<String, String> values;
    private final String errorCode;
    private final String errorMessage;

    private RemoteDictionary(String language, String url, Map<String, String> values,
                             String errorCode, String errorMessage) {
        this.language = language;
        this.url = url;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static RemoteDictionary available(String language, String url,
                                             Map<String, String> values) {
        return new RemoteDictionary(language, url, values, null, null);
    }

    public static RemoteDictionary unavailable(String language, String url,
                                               String errorCode, String errorMessage) {
        return new RemoteDictionary(language, url, Collections.<String, String>emptyMap(),
                errorCode, errorMessage);
    }

    public String getLanguage() {
        return language;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getValues() {
        return values;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isAvailable() {
        return errorCode == null;
    }
}
