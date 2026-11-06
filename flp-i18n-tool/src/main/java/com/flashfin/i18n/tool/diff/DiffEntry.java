package com.flashfin.i18n.tool.diff;

public final class DiffEntry {

    private final String key;
    private final String type;
    private final String localValue;
    private final String remoteValue;

    public DiffEntry(String key, String type, String localValue, String remoteValue) {
        this.key = key;
        this.type = type;
        this.localValue = localValue;
        this.remoteValue = remoteValue;
    }

    public String getKey() {
        return key;
    }

    public String getType() {
        return type;
    }

    public String getLocalValue() {
        return localValue;
    }

    public String getRemoteValue() {
        return remoteValue;
    }
}
