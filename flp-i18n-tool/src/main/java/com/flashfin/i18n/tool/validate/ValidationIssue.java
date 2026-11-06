package com.flashfin.i18n.tool.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ValidationIssue {

    public static final String INVALID_KEY_FORMAT = "INVALID_KEY_FORMAT";
    public static final String DUPLICATE_KEY = "DUPLICATE_KEY";
    public static final String EMPTY_VALUE = "EMPTY_VALUE";
    public static final String PLACEHOLDER_MISMATCH = "PLACEHOLDER_MISMATCH";

    private final String type;
    private final String severity;
    private final String key;
    private final String message;
    private final List<String> languages;
    private final String expected;
    private final Map<String, String> actual;

    public ValidationIssue(String type, String severity, String key, String message,
                           List<String> languages, String expected, Map<String, String> actual) {
        this.type = type;
        this.severity = severity;
        this.key = key;
        this.message = message;
        this.languages = Collections.unmodifiableList(new ArrayList<String>(languages));
        this.expected = expected;
        this.actual = Collections.unmodifiableMap(new LinkedHashMap<String, String>(actual));
    }

    public String getType() {
        return type;
    }

    public String getSeverity() {
        return severity;
    }

    public String getKey() {
        return key;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public String getExpected() {
        return expected;
    }

    public Map<String, String> getActual() {
        return actual;
    }
}
