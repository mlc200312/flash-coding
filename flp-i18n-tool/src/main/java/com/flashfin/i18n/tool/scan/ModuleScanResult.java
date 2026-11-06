package com.flashfin.i18n.tool.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleScanResult {

    private final ResolvedModule module;
    private final ClasspathResult classpath;
    private final int classesScanned;
    private final GeneratedKeys keys;
    private final List<String> classLoadFailures;

    public ModuleScanResult(ResolvedModule module, ClasspathResult classpath, int classesScanned,
                            GeneratedKeys keys, List<String> classLoadFailures) {
        this.module = module;
        this.classpath = classpath;
        this.classesScanned = classesScanned;
        this.keys = keys;
        this.classLoadFailures = Collections.unmodifiableList(
                new ArrayList<String>(classLoadFailures));
    }

    public ResolvedModule getModule() {
        return module;
    }

    public ClasspathResult getClasspath() {
        return classpath;
    }

    public int getClassesScanned() {
        return classesScanned;
    }

    public GeneratedKeys getKeys() {
        return keys;
    }

    public List<String> getClassLoadFailures() {
        return classLoadFailures;
    }
}
