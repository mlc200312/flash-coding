package com.flashfin.i18n.tool.sync;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UploadEntry {

    private final String module;
    private final String os;
    private final String key;
    private final String type;
    private final String language;
    private final String value;

    public UploadEntry(String module, String os, String key, String type, String language,
                       String value) {
        this.module = module;
        this.os = os;
        this.key = key;
        this.type = type;
        this.language = language;
        this.value = value;
    }

    public String getModule() {
        return module;
    }

    public String getOs() {
        return os;
    }

    public String getKey() {
        return key;
    }

    public String getType() {
        return type;
    }

    public String getLanguage() {
        return language;
    }

    public String getValue() {
        return value;
    }

    public String id() {
        return module + "|" + os + "|" + key + "|" + language;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("os", os);
        map.put("key", key);
        map.put("type", type);
        map.put("language", language);
        map.put("value", value);
        return map;
    }

    public Map<String, Object> toOutput() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("module", module);
        map.putAll(toMap());
        return map;
    }
}
