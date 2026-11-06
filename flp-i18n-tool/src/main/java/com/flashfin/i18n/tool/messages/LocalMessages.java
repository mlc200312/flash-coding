package com.flashfin.i18n.tool.messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalMessages {

    private final String language;
    private final Map<String, String> values;
    private final List<String> loadedFiles;
    private final List<String> missingFiles;

    public LocalMessages(String language, Map<String, String> values,
                         List<String> loadedFiles, List<String> missingFiles) {
        this.language = language;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
        this.loadedFiles = Collections.unmodifiableList(new ArrayList<String>(loadedFiles));
        this.missingFiles = Collections.unmodifiableList(new ArrayList<String>(missingFiles));
    }

    public String getLanguage() {
        return language;
    }

    public Map<String, String> getValues() {
        return values;
    }

    public List<String> getLoadedFiles() {
        return loadedFiles;
    }

    public List<String> getMissingFiles() {
        return missingFiles;
    }

    public String get(String key) {
        return values.get(key);
    }
}
