package com.flashfin.i18n.tool.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModulesConfig {

    private final int version;
    private final List<String> languages;
    private final PlatformConfig platform;
    private final Map<String, ModuleConfig> modules;
    private final Path workspaceRoot;

    public ModulesConfig(int version, List<String> languages, PlatformConfig platform,
                         Map<String, ModuleConfig> modules, Path workspaceRoot) {
        this.version = version;
        this.languages = Collections.unmodifiableList(languages);
        this.platform = platform;
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<String, ModuleConfig>(modules));
        this.workspaceRoot = workspaceRoot;
    }

    public int getVersion() {
        return version;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public PlatformConfig getPlatform() {
        return platform;
    }

    public Map<String, ModuleConfig> getModules() {
        return modules;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }
}
