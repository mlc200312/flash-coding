package com.flashfin.i18n.tool.fixtures;

public enum SampleStatusEnum implements FakeEnumerable {

    ACTIVE(1, "active"),
    DISABLED(2, "disabled");

    private final int code;
    private final String codeString;

    SampleStatusEnum(int code, String codeString) {
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
