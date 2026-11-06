package com.flashfin.i18n.tool.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashfin.i18n.tool.dictionary.DictionaryException;
import com.flashfin.i18n.tool.dictionary.LanguageCodes;
import com.flashfin.i18n.tool.sync.UploadEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class HttpPlatformClient implements PlatformClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long BASE_BACKOFF_MS = 200L;
    private static final long MAX_BACKOFF_MS = 2000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String saveUrl;
    private final String token;
    private final int timeoutMs;
    private final int maxRetries;

    public HttpPlatformClient(String saveUrl, String token, int timeoutMs, int maxRetries) {
        this.saveUrl = saveUrl;
        this.token = token;
        this.timeoutMs = Math.max(100, timeoutMs);
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public PlatformSaveResult save(UploadEntry entry) {
        byte[] body;
        try {
            body = requestBody(entry);
        } catch (DictionaryException e) {
            return PlatformSaveResult.failure(0, e.getCode(), e.getMessage(), false);
        } catch (IOException e) {
            return PlatformSaveResult.failure(0, "UPLOAD_REQUEST_INVALID",
                    "构造上传请求失败: " + e.getClass().getSimpleName(), false);
        }

        int attempts = maxRetries + 1;
        PlatformSaveResult last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            last = attemptSave(body);
            if (last.isOk() || !last.isRetryable() || attempt == attempts) {
                return last;
            }
            if (!sleep(backoffMs(attempt))) {
                return PlatformSaveResult.failure(last.getStatus(), last.getCode(),
                        last.getMessage() + "（重试被中断）", false);
            }
        }
        return last;
    }

    private byte[] requestBody(UploadEntry entry) throws DictionaryException, IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("os", entry.getOs());
        root.put("t_key", entry.getKey());
        root.put("loading", true);
        root.putObject("langs").put(LanguageCodes.platformField(entry.getLanguage()),
                entry.getValue());
        return MAPPER.writeValueAsBytes(root);
    }

    private PlatformSaveResult attemptSave(byte[] body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(saveUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("User-Agent", "flp-i18n-cli/0.1.0");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }

            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                drain(connection.getInputStream());
                return PlatformSaveResult.success(status);
            }
            drain(connection.getErrorStream());
            if (status == 401 || status == 403) {
                return PlatformSaveResult.failure(status, "UPLOAD_UNAUTHORIZED",
                        "翻译平台拒绝上传凭证 (HTTP " + status + ")", false);
            }
            boolean retryable = status == 408 || status == 429 || status >= 500;
            return PlatformSaveResult.failure(status, "UPLOAD_FAILED",
                    "上传失败 (HTTP " + status + ")", retryable);
        } catch (IOException e) {
            return PlatformSaveResult.failure(0, "UPLOAD_FAILED",
                    "上传失败 (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void drain(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() < MAX_RESPONSE_BYTES) {
                    buffer.write(chunk, 0, Math.min(read, MAX_RESPONSE_BYTES - buffer.size()));
                }
            }
        } catch (IOException ignored) {
            // response body is not used
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private long backoffMs(int attempt) {
        return Math.min(BASE_BACKOFF_MS * attempt, MAX_BACKOFF_MS);
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
