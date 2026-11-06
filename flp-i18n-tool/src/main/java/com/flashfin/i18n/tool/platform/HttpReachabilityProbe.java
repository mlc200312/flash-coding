package com.flashfin.i18n.tool.platform;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class HttpReachabilityProbe implements ReachabilityProbe {

    private static final String USER_AGENT = "flp-i18n-cli/0.1.0";

    @Override
    public ProbeResult probe(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        long start = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");

            int status = connection.getResponseCode();
            long latencyMs = elapsedMillis(start);
            return new ProbeResult(status < 500, Integer.valueOf(status), latencyMs, null);
        } catch (IOException e) {
            long latencyMs = elapsedMillis(start);
            String detail = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) {
                detail = detail + ": " + e.getMessage().trim();
            }
            return new ProbeResult(false, null, latencyMs, detail);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }
}
