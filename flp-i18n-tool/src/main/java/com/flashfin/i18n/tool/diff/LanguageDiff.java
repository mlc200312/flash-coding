package com.flashfin.i18n.tool.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LanguageDiff {

    private final String language;
    private final boolean remoteAvailable;
    private final List<DiffEntry> missingOnPlatform;
    private final List<DiffEntry> missingLocally;
    private final List<DiffEntry> zhConflicts;

    public LanguageDiff(String language, boolean remoteAvailable,
                        List<DiffEntry> missingOnPlatform, List<DiffEntry> missingLocally,
                        List<DiffEntry> zhConflicts) {
        this.language = language;
        this.remoteAvailable = remoteAvailable;
        this.missingOnPlatform = Collections.unmodifiableList(
                new ArrayList<DiffEntry>(missingOnPlatform));
        this.missingLocally = Collections.unmodifiableList(
                new ArrayList<DiffEntry>(missingLocally));
        this.zhConflicts = Collections.unmodifiableList(
                new ArrayList<DiffEntry>(zhConflicts));
    }

    public String getLanguage() {
        return language;
    }

    public boolean isRemoteAvailable() {
        return remoteAvailable;
    }

    public List<DiffEntry> getMissingOnPlatform() {
        return missingOnPlatform;
    }

    public List<DiffEntry> getMissingLocally() {
        return missingLocally;
    }

    public List<DiffEntry> getZhConflicts() {
        return zhConflicts;
    }
}
