package com.flashfin.i18n.tool.security;

import java.time.Instant;

public final class TokenState {

    private final boolean present;
    private final boolean jwt;
    private final Instant expiresAt;
    private final Long expiresInSeconds;
    private final String parseError;

    public TokenState(boolean present, boolean jwt, Instant expiresAt, Long expiresInSeconds, String parseError) {
        this.present = present;
        this.jwt = jwt;
        this.expiresAt = expiresAt;
        this.expiresInSeconds = expiresInSeconds;
        this.parseError = parseError;
    }

    public static TokenState absent() {
        return new TokenState(false, false, null, null, null);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isJwt() {
        return jwt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public String getParseError() {
        return parseError;
    }
}
