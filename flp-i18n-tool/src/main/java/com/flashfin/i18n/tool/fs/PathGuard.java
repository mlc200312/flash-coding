package com.flashfin.i18n.tool.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PathGuard {

    public static final String ENV_ALLOWED_ROOTS = "FLP_I18N_ALLOWED_ROOTS";

    private final Path workspaceRoot;
    private final List<Path> allowedRoots;

    private PathGuard(Path workspaceRoot, List<Path> allowedRoots) {
        this.workspaceRoot = workspaceRoot;
        this.allowedRoots = Collections.unmodifiableList(new ArrayList<Path>(allowedRoots));
    }

    public static PathGuard create(Path workspaceRoot, Map<String, String> env) throws PathGuardException {
        Path workspaceReal = realDirectory(workspaceRoot, "workspaceRoot");
        List<Path> roots = parseAllowedRoots(env);
        if (roots.isEmpty()) {
            roots.add(workspaceReal);
        }
        boolean allowed = false;
        for (Path root : roots) {
            if (workspaceReal.startsWith(root)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED,
                    "workspaceRoot 不在允许根目录内: " + workspaceReal);
        }
        return new PathGuard(workspaceReal, roots);
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path resolveRepoRoot(String repoRoot) throws PathGuardException {
        return resolveWithin(workspaceRoot, repoRoot, "repoRoot");
    }

    public Path resolveDomainPath(Path repoRoot, String domainPath) throws PathGuardException {
        return resolveWithin(repoRoot, domainPath, "domainPath");
    }

    public Path requireDirectory(Path path, String label) throws PathGuardException {
        Path real = realDirectory(path, label);
        requireAllowed(real, label);
        return real;
    }

    public void requireAllowed(Path path, String label) throws PathGuardException {
        Path real = toRealPathIfExists(path, label);
        for (Path root : allowedRoots) {
            if (real.startsWith(root)) {
                return;
            }
        }
        throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED,
                label + " 不在允许根目录内: " + real);
    }

    private Path resolveWithin(Path base, String relative, String label) throws PathGuardException {
        if (relative == null || relative.trim().isEmpty()) {
            throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED, label + " 为空");
        }
        rejectTraversal(relative, label);

        Path value;
        try {
            value = Paths.get(relative.trim());
        } catch (InvalidPathException e) {
            throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED, label + " 路径非法: " + relative);
        }

        Path basePath = base.toAbsolutePath().normalize();
        Path candidate = (value.isAbsolute() ? value : basePath.resolve(value)).normalize();
        if (!candidate.startsWith(basePath)) {
            throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED,
                    label + " 越出基准目录: " + candidate);
        }
        return requireDirectory(candidate, label);
    }

    private static Path realDirectory(Path path, String label) throws PathGuardException {
        if (path == null) {
            throw new PathGuardException(PathGuardException.PATH_NOT_FOUND, label + " 为空");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new PathGuardException(PathGuardException.PATH_NOT_FOUND, label + " 不存在: " + absolute);
        }
        Path real = toRealPathIfExists(absolute, label);
        if (!Files.isDirectory(real)) {
            throw new PathGuardException(PathGuardException.PATH_NOT_FOUND, label + " 不是目录: " + real);
        }
        return real;
    }

    private static Path toRealPathIfExists(Path path, String label) throws PathGuardException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return absolute;
        }
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            throw new PathGuardException(PathGuardException.PATH_NOT_FOUND,
                    label + " 无法解析真实路径: " + absolute);
        }
    }

    private static List<Path> parseAllowedRoots(Map<String, String> env) throws PathGuardException {
        List<Path> result = new ArrayList<Path>();
        String raw = env == null ? null : env.get(ENV_ALLOWED_ROOTS);
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String part : raw.split(Pattern.quote(File.pathSeparator))) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            Path path;
            try {
                path = Paths.get(value);
            } catch (InvalidPathException e) {
                throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED,
                        ENV_ALLOWED_ROOTS + " 路径非法: " + value);
            }
            if (!path.isAbsolute()) {
                path = Paths.get("").toAbsolutePath().resolve(path);
            }
            result.add(realDirectory(path, ENV_ALLOWED_ROOTS));
        }
        if (result.isEmpty()) {
            throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED, ENV_ALLOWED_ROOTS + " 为空");
        }
        return result;
    }

    private static void rejectTraversal(String relative, String label) throws PathGuardException {
        String normalized = relative.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new PathGuardException(PathGuardException.PATH_NOT_ALLOWED,
                        label + " 不得包含 ..: " + relative);
            }
        }
    }
}
