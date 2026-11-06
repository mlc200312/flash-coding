package com.flashfin.i18n.tool.fixtures;

public enum MixedEnum implements FakeEnumerable, FakeResultCode {

    MIXED(9);

    private final int code;

    MixedEnum(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getCodeString() {
        return String.valueOf(code);
    }
}
