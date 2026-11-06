package com.flashfin.i18n.tool.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ModuleConfig {

    public static final Set<String> SUPPORTED_OS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("flp-code", "flp-enum", "common-code", "common-enum")));

    private final String name;
    private final String repoRoot;
    private final String domainPath;
    private final String os;
    private final List<String> packages;
    private final String codeFile;
    private final String enumFile;

    public ModuleConfig(String name, String repoRoot, String domainPath, String os,
                        List<String> packages, String codeFile, String enumFile) {
        this.name = name;
        this.repoRoot = repoRoot;
        this.domainPath = domainPath;
        this.os = os;
        this.packages = Collections.unmodifiableList(packages);
        this.codeFile = codeFile;
        this.enumFile = enumFile;
    }

    public String getName() {
        return name;
    }

    public String getRepoRoot() {
        return repoRoot;
    }

    public String getDomainPath() {
        return domainPath;
    }

    public String getOs() {
        return os;
    }

    public List<String> getPackages() {
        return packages;
    }

    public String getCodeFile() {
        return codeFile;
    }

    public String getEnumFile() {
        return enumFile;
    }
}
