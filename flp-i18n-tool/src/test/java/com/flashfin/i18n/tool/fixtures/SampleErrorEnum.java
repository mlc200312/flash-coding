package com.flashfin.i18n.tool.fixtures;

public enum SampleErrorEnum implements FakeResultCode {

    SUCCESS(0),
    SYSTEM_ERROR(100);

    private final int code;

    SampleErrorEnum(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
