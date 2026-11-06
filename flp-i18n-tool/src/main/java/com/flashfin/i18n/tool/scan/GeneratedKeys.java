package com.flashfin.i18n.tool.scan;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GeneratedKeys {

    private final Map<String, String> codeKeys = new LinkedHashMap<String, String>();
    private final Map<String, String> enumKeys = new LinkedHashMap<String, String>();
    private final Map<String, DuplicateKey> duplicates = new LinkedHashMap<String, DuplicateKey>();

    void addCodeKey(String key, String source) {
        add(codeKeys, "code", key, source);
    }

    void addEnumKey(String key, String source) {
        add(enumKeys, "enum", key, source);
    }

    private void add(Map<String, String> target, String type, String key, String source) {
        String previous = target.get(key);
        if (previous != null && !previous.equals(source)) {
            String id = type + ":" + key;
            if (!duplicates.containsKey(id)) {
                duplicates.put(id, new DuplicateKey(key, type, previous, source));
            }
        }
        target.put(key, source);
    }

    public Map<String, String> getCodeKeys() {
        return codeKeys;
    }

    public Map<String, String> getEnumKeys() {
        return enumKeys;
    }

    public Map<String, DuplicateKey> getDuplicates() {
        return duplicates;
    }

    public static final class DuplicateKey {

        private final String key;
        private final String type;
        private final String firstSource;
        private final String duplicateSource;

        public DuplicateKey(String key, String type, String firstSource, String duplicateSource) {
            this.key = key;
            this.type = type;
            this.firstSource = firstSource;
            this.duplicateSource = duplicateSource;
        }

        public String getKey() {
            return key;
        }

        public String getType() {
            return type;
        }

        public String getFirstSource() {
            return firstSource;
        }

        public String getDuplicateSource() {
            return duplicateSource;
        }
    }
}
