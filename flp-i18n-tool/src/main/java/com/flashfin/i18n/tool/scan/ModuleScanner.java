package com.flashfin.i18n.tool.scan;

import java.util.TreeSet;

public final class ModuleScanner {

    private final ModuleClasspathBuilder classpathBuilder;
    private final ClassScanner classScanner;
    private final KeyGenerator keyGenerator;

    public ModuleScanner(ModuleClasspathBuilder classpathBuilder, ClassScanner classScanner,
                         KeyGenerator keyGenerator) {
        this.classpathBuilder = classpathBuilder;
        this.classScanner = classScanner;
        this.keyGenerator = keyGenerator;
    }

    public ModuleScanResult scan(ResolvedModule module) throws ScanException {
        ClasspathResult classpath = classpathBuilder.build(module);
        TreeSet<String> classNames = classScanner.scanClassNames(classpath.getEntries(),
                module.getPackages());
        ClassScanner.LoadedClasses loaded = classScanner.load(classpath.getEntries(), classNames);
        try {
            GeneratedKeys keys = keyGenerator.generate(loaded.getClasses(), loaded.getLoader());
            return new ModuleScanResult(module, classpath, classNames.size(), keys,
                    loaded.getFailures());
        } finally {
            loaded.close();
        }
    }
}
