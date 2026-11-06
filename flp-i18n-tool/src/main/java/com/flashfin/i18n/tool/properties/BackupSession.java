package com.flashfin.i18n.tool.properties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class BackupSession {

    private final Path directory;

    private BackupSession(Path directory) {
        this.directory = directory;
    }

    public static BackupSession create(Path toolDataDir) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path directory = toolDataDir.resolve("backup").resolve(stamp);
        int suffix = 1;
        while (Files.exists(directory)) {
            directory = toolDataDir.resolve("backup").resolve(stamp + "-" + suffix++);
        }
        Files.createDirectories(directory);
        return new BackupSession(directory);
    }

    public Path backup(Path file, String module, Path moduleRoot) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Path relative = moduleRoot.relativize(file);
        Path target = directory.resolve(safe(module)).resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public Path getDirectory() {
        return directory;
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "module" : value.trim();
    }
}
