package com.flashfin.i18n.tool.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class HttpDictionaryClient implements DictionaryClient {

    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final long BASE_BACKOFF_MS = 200L;
    private static final long MAX_BACKOFF_MS = 2000L;
    private static final Pattern SAFE_OS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final int timeoutMs;
    private final int maxRetries;

    public HttpDictionaryClient(String baseUrl, int timeoutMs, int maxRetries) {
        this.baseUrl = trimTrailingSlashes(baseUrl);
        this.timeoutMs = Math.max(100, timeoutMs);
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public RemoteDictionary fetch(String os, String language) {
        String url;
        try {
            url = buildUrl(os, language);
        } catch (DictionaryException e) {
            return RemoteDictionary.unavailable(language, baseUrl, e.getCode(), e.getMessage());
        }

        int attempts = maxRetries + 1;
        String lastCode = DictionaryException.FETCH_FAILED;
        String lastMessage = "未知错误";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Attempt result = attemptFetch(url);
            if (result.values != null) {
                return RemoteDictionary.available(language, url, result.values);
            }
            lastCode = result.code;
            lastMessage = result.message;
            if (!result.retryable || attempt == attempts) {
                break;
            }
            if (!sleep(backoffMs(attempt))) {
                lastMessage = lastMessage + "（重试被中断）";
                break;
            }
        }
        return RemoteDictionary.unavailable(language, url, lastCode, lastMessage);
    }

    private Attempt attemptFetch(String url) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "flp-i18n-cli/0.1.0");

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                byte[] body = readBody(connection.getInputStream());
                return Attempt.success(parseDictionary(body));
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND || status == 410) {
                return Attempt.failure(DictionaryException.NOT_FOUND,
                        "字典不存在: " + url + " (HTTP " + status + ")", false);
            }
            boolean retryable = status == 408 || status == 429 || status >= 500;
            return Attempt.failure(DictionaryException.FETCH_FAILED,
                    "拉取字典失败: " + url + " (HTTP " + status + ")", retryable);
        } catch (DictionaryException e) {
            return Attempt.failure(e.getCode(), e.getMessage(), e.isRetryable());
        } catch (IOException e) {
            return Attempt.failure(DictionaryException.FETCH_FAILED,
                    "拉取字典失败: " + url + " (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Map<String, String> parseDictionary(byte[] body) throws DictionaryException {
        if (body.length == 0) {
            throw new DictionaryException(DictionaryException.RESPONSE_INVALID, "字典响应为空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new DictionaryException(DictionaryException.RESPONSE_INVALID,
                    "字典响应不是合法 JSON", false, e);
        }
        if (root == null || !root.isObject()) {
            throw new DictionaryException(DictionaryException.RESPONSE_INVALID,
                    "字典响应必须是 JSON 对象");
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                values.put(field.getKey(), "");
            } else if (value.isValueNode()) {
                values.put(field.getKey(), value.asText());
            } else {
                throw new DictionaryException(DictionaryException.RESPONSE_INVALID,
                        "字典值必须是标量: " + field.getKey());
            }
        }
        return values;
    }

    private byte[] readBody(InputStream input) throws IOException, DictionaryException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_BODY_BYTES) {
                    throw new DictionaryException(DictionaryException.RESPONSE_TOO_LARGE,
                            "字典响应超过 " + MAX_BODY_BYTES + " 字节");
                }
                buffer.write(chunk, 0, read);
            }
        } finally {
            input.close();
        }
        return buffer.toByteArray();
    }

    private String buildUrl(String os, String language) throws DictionaryException {
        if (os == null || !SAFE_OS.matcher(os.trim()).matches()) {
            throw new DictionaryException(DictionaryException.INVALID_LANGUAGE, "os 不合法: " + os);
        }
        return baseUrl + "/" + os.trim() + "/lang/"
                + LanguageCodes.cdnPath(language) + ".json";
    }

    private long backoffMs(int attempt) {
        long value = BASE_BACKOFF_MS * attempt;
        return Math.min(value, MAX_BACKOFF_MS);
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

    private static String trimTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static final class Attempt {

        private final Map<String, String> values;
        private final String code;
        private final String message;
        private final boolean retryable;

        private Attempt(Map<String, String> values, String code, String message, boolean retryable) {
            this.values = values;
            this.code = code;
            this.message = message;
            this.retryable = retryable;
        }

        private static Attempt success(Map<String, String> values) {
            return new Attempt(values, null, null, false);
        }

        private static Attempt failure(String code, String message, boolean retryable) {
            return new Attempt(null, code, message, retryable);
        }
    }
}
