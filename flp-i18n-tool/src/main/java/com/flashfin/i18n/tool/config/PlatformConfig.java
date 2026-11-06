package com.flashfin.i18n.tool.config;

public final class PlatformConfig {

    private final String saveUrl;
    private final String dictionaryBase;
    private final int timeoutMs;
    private final int maxRetries;

    public PlatformConfig(String saveUrl, String dictionaryBase, int timeoutMs, int maxRetries) {
        this.saveUrl = saveUrl;
        this.dictionaryBase = dictionaryBase;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
    }

    public String getSaveUrl() {
        return saveUrl;
    }

    public String getDictionaryBase() {
        return dictionaryBase;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }
}
