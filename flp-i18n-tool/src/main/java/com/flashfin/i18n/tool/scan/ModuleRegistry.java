package com.flashfin.i18n.tool.scan;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.config.ModulesConfig;
import com.flashfin.i18n.tool.fs.PathGuard;
import com.flashfin.i18n.tool.fs.PathGuardException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ModuleRegistry {

    private final ModulesConfig config;
    private final PathGuard guard;

    private ModuleRegistry(ModulesConfig config, PathGuard guard) {
        this.config = config;
        this.guard = guard;
    }

    public static ModuleRegistry create(ModulesConfig config, PathGuard guard) {
        return new ModuleRegistry(config, guard);
    }

    public List<String> names() {
        return new ArrayList<String>(config.getModules().keySet());
    }

    public List<ModuleConfig> select(String moduleName, String os) throws ScanException {
        List<ModuleConfig> result = new ArrayList<ModuleConfig>();
        if (moduleName != null) {
            ModuleConfig module = config.getModules().get(moduleName);
            if (module == null) {
                throw new ScanException(ScanException.MODULE_NOT_FOUND,
                        "未知模块: " + moduleName + "，可选值 " + names());
            }
            if (os == null || os.equals(module.getOs())) {
                result.add(module);
            }
            return result;
        }
        for (ModuleConfig module : config.getModules().values()) {
            if (os == null || os.equals(module.getOs())) {
                result.add(module);
            }
        }
        return result;
    }

    public ResolvedModule resolve(ModuleConfig module) throws ScanException {
        Path repoRoot;
        Path domainRoot;
        try {
            repoRoot = guard.resolveRepoRoot(module.getRepoRoot());
            domainRoot = guard.resolveDomainPath(repoRoot, module.getDomainPath());
        } catch (PathGuardException e) {
            throw new ScanException(e.getCode(),
                    "模块 " + module.getName() + " 路径非法: " + e.getMessage(), e);
        }
        Path classesDir = domainRoot.resolve("target").resolve("classes");
        if (!Files.isDirectory(classesDir)) {
            throw new ScanException(ScanException.CLASSES_NOT_FOUND,
                    "模块 " + module.getName() + " 未编译，缺少 " + classesDir
                            + "；先在模块目录执行 mvn -o compile");
        }
        return new ResolvedModule(module, repoRoot, domainRoot, classesDir);
    }
}
