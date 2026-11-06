package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.report.JsonEnvelope;

public final class CommandResult {

    private final int exitCode;
    private final JsonEnvelope envelope;

    public CommandResult(int exitCode, JsonEnvelope envelope) {
        this.exitCode = exitCode;
        this.envelope = envelope;
    }

    public int getExitCode() {
        return exitCode;
    }

    public JsonEnvelope getEnvelope() {
        return envelope;
    }
}
