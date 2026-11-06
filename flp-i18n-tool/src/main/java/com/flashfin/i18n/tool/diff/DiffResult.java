package com.flashfin.i18n.tool.diff;

import com.flashfin.i18n.tool.dictionary.RemoteDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiffResult {

    private final Map<String, LanguageDiff> languages;
    private final List<RemoteDictionary> remoteUnavailable;
    private final int missingOnPlatformTotal;
    private final int missingLocallyTotal;
    private final int zhConflictsTotal;

    public DiffResult(Map<String, LanguageDiff> languages, List<RemoteDictionary> remoteUnavailable,
                      int missingOnPlatformTotal, int missingLocallyTotal, int zhConflictsTotal) {
        this.languages = Collections.unmodifiableMap(
                new LinkedHashMap<String, LanguageDiff>(languages));
        this.remoteUnavailable = Collections.unmodifiableList(
                new ArrayList<RemoteDictionary>(remoteUnavailable));
        this.missingOnPlatformTotal = missingOnPlatformTotal;
        this.missingLocallyTotal = missingLocallyTotal;
        this.zhConflictsTotal = zhConflictsTotal;
    }

    public Map<String, LanguageDiff> getLanguages() {
        return languages;
    }

    public List<RemoteDictionary> getRemoteUnavailable() {
        return remoteUnavailable;
    }

    public int getMissingOnPlatformTotal() {
        return missingOnPlatformTotal;
    }

    public int getMissingLocallyTotal() {
        return missingLocallyTotal;
    }

    public int getZhConflictsTotal() {
        return zhConflictsTotal;
    }
}
