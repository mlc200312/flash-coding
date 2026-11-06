package com.flashfin.i18n.tool.fixtures;

public final class NotAnEnum implements FakeEnumerable {

    @Override
    public int getCode() {
        return 1;
    }

    @Override
    public String getCodeString() {
        return "1";
    }
}
