package com.flashfin.i18n.tool.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GitStatus {

    public static final String GIT_DIRTY = "GIT_DIRTY";
    public static final String NOT_A_GIT_REPO = "NOT_A_GIT_REPO";
    public static final String GIT_UNAVAILABLE = "GIT_UNAVAILABLE";

    private final boolean clean;
    private final String code;
    private final String message;
    private final List<String> entries;

    private GitStatus(boolean clean, String code, String message, List<String> entries) {
        this.clean = clean;
        this.code = code;
        this.message = message;
        this.entries = Collections.unmodifiableList(new ArrayList<String>(entries));
    }

    static GitStatus clean() {
        return new GitStatus(true, null, null, Collections.<String>emptyList());
    }

    static GitStatus dirty(List<String> entries) {
        return new GitStatus(false, GIT_DIRTY,
                "目标仓库存在未提交变更，共 " + entries.size() + " 项", entries);
    }

    static GitStatus error(String code, String message) {
        return new GitStatus(false, code, message, Collections.<String>emptyList());
    }

    public boolean isClean() {
        return clean;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getEntries() {
        return entries;
    }
}
