package com.flashfin.i18n.tool.scan;

import com.flashfin.i18n.tool.config.ModuleConfig;

import java.nio.file.Path;
import java.util.List;

public final class ResolvedModule {

    private final ModuleConfig config;
    private final Path repoRoot;
    private final Path domainRoot;
    private final Path classesDir;

    public ResolvedModule(ModuleConfig config, Path repoRoot, Path domainRoot, Path classesDir) {
        this.config = config;
        this.repoRoot = repoRoot;
        this.domainRoot = domainRoot;
        this.classesDir = classesDir;
    }

    public String getName() {
        return config.getName();
    }

    public String getOs() {
        return config.getOs();
    }

    public List<String> getPackages() {
        return config.getPackages();
    }

    public ModuleConfig getConfig() {
        return config;
    }

    public Path getRepoRoot() {
        return repoRoot;
    }

    public Path getDomainRoot() {
        return domainRoot;
    }

    public Path getClassesDir() {
        return classesDir;
    }
}
