package com.flashfin.i18n.tool.platform;

public final class ProbeResult {

    private final boolean reachable;
    private final Integer statusCode;
    private final long latencyMs;
    private final String error;

    public ProbeResult(boolean reachable, Integer statusCode, long latencyMs, String error) {
        this.reachable = reachable;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.error = error;
    }

    public boolean isReachable() {
        return reachable;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getError() {
        return error;
    }
}
