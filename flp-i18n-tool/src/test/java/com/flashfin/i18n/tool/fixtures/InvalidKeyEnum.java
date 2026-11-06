package com.flashfin.i18n.tool.fixtures;

@FakeStringEnumerable
public enum InvalidKeyEnum implements FakeEnumerable {

    BROKEN(1, "bad key");

    private final int code;
    private final String codeString;

    InvalidKeyEnum(int code, String codeString) {
        this.code = code;
        this.codeString = codeString;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getCodeString() {
        return codeString;
    }
}
