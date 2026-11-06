package com.flashfin.i18n.tool.scan;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ClassScanner {

    public TreeSet<String> scanClassNames(List<Path> classpath, Collection<String> packages)
            throws ScanException {
        TreeSet<String> names = new TreeSet<String>();
        for (Path entry : classpath) {
            if (Files.isDirectory(entry)) {
                for (String pkg : packages) {
                    scanDirectory(entry, pkg, names);
                }
            } else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                for (String pkg : packages) {
                    scanJar(entry, pkg, names);
                }
            }
        }
        return names;
    }

    public LoadedClasses load(List<Path> classpath, Collection<String> classNames) throws ScanException {
        URL[] urls = new URL[classpath.size()];
        try {
            for (int i = 0; i < classpath.size(); i++) {
                urls[i] = classpath.get(i).toUri().toURL();
            }
        } catch (IOException e) {
            throw new ScanException(ScanException.SCAN_FAILED, "无法构建扫描类加载器 URL", e);
        }
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        URLClassLoader loader = new URLClassLoader(urls, parent);

        List<Class<?>> classes = new ArrayList<Class<?>>();
        List<String> failures = new ArrayList<String>();
        for (String className : classNames) {
            try {
                classes.add(Class.forName(className, false, loader));
            } catch (ClassNotFoundException e) {
                failures.add(className + ": ClassNotFoundException");
            } catch (LinkageError e) {
                failures.add(className + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
            } catch (SecurityException e) {
                failures.add(className + ": SecurityException");
            }
        }
        return new LoadedClasses(loader, classes, failures);
    }

    private void scanDirectory(final Path root, final String packageName, final TreeSet<String> names)
            throws ScanException {
        final Path packageDir = root.resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(packageDir)) {
            return;
        }
        try {
            Files.walkFileTree(packageDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".class")) {
                        String className = toClassName(root.relativize(file));
                        if (isLoadableName(className)) {
                            names.add(className);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ScanException(ScanException.SCAN_FAILED,
                    "扫描目录失败: " + packageDir, e);
        }
    }

    private void scanJar(Path jarPath, String packageName, TreeSet<String> names) throws ScanException {
        String prefix = packageName.replace('.', '/') + "/";
        JarFile jar = null;
        try {
            jar = new JarFile(jarPath.toFile());
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                if (isLoadableName(className)) {
                    names.add(className);
                }
            }
        } catch (IOException e) {
            throw new ScanException(ScanException.SCAN_FAILED, "扫描 jar 失败: " + jarPath, e);
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                    // read-only jar
                }
            }
        }
    }

    private String toClassName(Path relative) {
        StringBuilder sb = new StringBuilder(relative.toString().length());
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(relative.getName(i).toString());
        }
        String name = sb.toString();
        return name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
    }

    private boolean isLoadableName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return !"package-info".equals(simple) && !"module-info".equals(simple);
    }

    public static final class LoadedClasses implements Closeable {

        private final URLClassLoader loader;
        private final List<Class<?>> classes;
        private final List<String> failures;

        private LoadedClasses(URLClassLoader loader, List<Class<?>> classes, List<String> failures) {
            this.loader = loader;
            this.classes = Collections.unmodifiableList(new ArrayList<Class<?>>(classes));
            this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
        }

        public ClassLoader getLoader() {
            return loader;
        }

        public List<Class<?>> getClasses() {
            return classes;
        }

        public List<String> getFailures() {
            return failures;
        }

        @Override
        public void close() {
            try {
                loader.close();
            } catch (IOException ignored) {
                // URLs are file-based and read-only
            }
        }
    }
}
