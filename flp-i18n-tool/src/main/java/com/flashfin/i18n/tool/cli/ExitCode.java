package com.flashfin.i18n.tool.cli;

public final class ExitCode {

    public static final int OK = 0;
    public static final int INTERNAL_ERROR = 1;
    public static final int INVALID = 2;
    public static final int REMOTE_ERROR = 3;
    public static final int WRITE_REJECTED = 4;

    private ExitCode() {
    }
}
