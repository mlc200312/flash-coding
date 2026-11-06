package com.flashfin.i18n.tool.fixtures;

public final class DuplicateEnums {

    private DuplicateEnums() {
    }

    public enum DupOne implements FakeResultCode {
        ONLY(7);

        private final int code;

        DupOne(int code) {
            this.code = code;
        }

        @Override
        public int getCode() {
            return code;
        }
    }

    public enum DupTwo implements FakeResultCode {
        ONLY(7);

        private final int code;

        DupTwo(int code) {
            this.code = code;
        }

        @Override
        public int getCode() {
            return code;
        }
    }
}
