package com.flashfin.i18n.tool.report;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"ok", "command", "data", "warnings", "error"})
public final class JsonEnvelope {

    private final boolean ok;
    private final String command;
    private final Object data;
    private final List<String> warnings;
    private final ErrorInfo error;

    private JsonEnvelope(boolean ok, String command, Object data, List<String> warnings, ErrorInfo error) {
        this.ok = ok;
        this.command = command;
        this.data = data;
        this.warnings = warnings;
        this.error = error;
    }

    public static JsonEnvelope success(String command, Object data) {
        return new JsonEnvelope(true, command, data, new ArrayList<String>(), null);
    }

    public static JsonEnvelope success(String command, Object data, List<String> warnings) {
        return new JsonEnvelope(true, command, data, new ArrayList<String>(warnings), null);
    }

    public static JsonEnvelope failure(String command, Object data, ErrorInfo error) {
        return new JsonEnvelope(false, command, data, new ArrayList<String>(), error);
    }

    public static JsonEnvelope failure(String command, Object data, List<String> warnings, ErrorInfo error) {
        return new JsonEnvelope(false, command, data, new ArrayList<String>(warnings), error);
    }

    public boolean isOk() {
        return ok;
    }

    public String getCommand() {
        return command;
    }

    public Object getData() {
        return data;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public ErrorInfo getError() {
        return error;
    }
}
