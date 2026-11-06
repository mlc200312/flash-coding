package com.flashfin.i18n.tool.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;

public final class TokenInspector {

    public static final String ENV_TOKEN = "FLP_I18N_TOKEN";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenInspector() {
    }

    public static TokenState inspect(String token, Instant now) {
        if (token == null || token.trim().isEmpty()) {
            return TokenState.absent();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return new TokenState(true, false, null, null, "Token 不是标准 JWT，无法读取 exp");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(payload);
            JsonNode exp = node == null ? null : node.get("exp");
            if (exp == null || !exp.isNumber()) {
                return new TokenState(true, true, null, null, "JWT payload 中缺少 exp");
            }
            Instant expiresAt = Instant.ofEpochSecond(exp.asLong());
            long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
            return new TokenState(true, true, expiresAt, Long.valueOf(seconds), null);
        } catch (Exception e) {
            return new TokenState(true, true, null, null, "无法解析 JWT payload");
        }
    }
}
