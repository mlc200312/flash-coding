package com.flashfin.i18n.tool.fs;

public final class PathGuardException extends Exception {

    public static final String PATH_NOT_ALLOWED = "PATH_NOT_ALLOWED";
    public static final String PATH_NOT_FOUND = "PATH_NOT_FOUND";

    private final String code;

    public PathGuardException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
