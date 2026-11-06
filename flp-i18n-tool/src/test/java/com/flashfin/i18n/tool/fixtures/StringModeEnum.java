package com.flashfin.i18n.tool.fixtures;

@FakeStringEnumerable
public enum StringModeEnum implements FakeEnumerable {

    FIRST(10, "F"),
    SECOND(11, "S");

    private final int code;
    private final String codeString;

    StringModeEnum(int code, String codeString) {
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
