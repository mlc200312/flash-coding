package com.flashfin.i18n.tool.scan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClasspathResult {

    private final Path classesDir;
    private final List<Path> entries;
    private final boolean builtOnline;

    public ClasspathResult(Path classesDir, List<Path> entries, boolean builtOnline) {
        this.classesDir = classesDir;
        this.entries = Collections.unmodifiableList(new ArrayList<Path>(entries));
        this.builtOnline = builtOnline;
    }

    public Path getClassesDir() {
        return classesDir;
    }

    public List<Path> getEntries() {
        return entries;
    }

    public boolean isBuiltOnline() {
        return builtOnline;
    }
}
