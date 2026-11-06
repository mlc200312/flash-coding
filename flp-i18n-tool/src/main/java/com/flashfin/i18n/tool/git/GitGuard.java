package com.flashfin.i18n.tool.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitGuard {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final long TIMEOUT_SECONDS = 30L;

    public GitStatus check(Path repoRoot) {
        CommandResult inside = run(repoRoot, "rev-parse", "--is-inside-work-tree");
        if (inside.failedToStart) {
            return GitStatus.error(GitStatus.GIT_UNAVAILABLE,
                    "无法执行 git: " + inside.error);
        }
        if (inside.exitCode != 0 || !"true".equals(inside.stdout.trim())) {
            return GitStatus.error(GitStatus.NOT_A_GIT_REPO,
                    "目标目录不是 git 工作区: " + repoRoot);
        }

        CommandResult status = run(repoRoot, "status", "--porcelain", "--untracked-files=normal");
        if (status.failedToStart) {
            return GitStatus.error(GitStatus.GIT_UNAVAILABLE,
                    "无法执行 git: " + status.error);
        }
        if (status.exitCode != 0) {
            return GitStatus.error(GitStatus.GIT_UNAVAILABLE,
                    "git status 失败: " + firstLine(status.stderr));
        }
        List<String> dirty = new ArrayList<String>();
        for (String line : status.stdout.split("\n")) {
            if (line.trim().isEmpty() || isToolData(line)) {
                continue;
            }
            dirty.add(line);
        }
        if (dirty.isEmpty()) {
            return GitStatus.clean();
        }
        return GitStatus.dirty(dirty);
    }

    private boolean isToolData(String porcelainLine) {
        String path = porcelainLine.length() > 3 ? porcelainLine.substring(3).trim() : "";
        int rename = path.indexOf(" -> ");
        if (rename >= 0) {
            path = path.substring(rename + 4).trim();
        }
        path = path.replace('\\', '/');
        return path.startsWith(".i18n-tool/") || path.contains("/.i18n-tool/");
    }

    private CommandResult run(Path repoRoot, String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repoRoot.toString());
        for (String argument : arguments) {
            command.add(argument);
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StreamCollector out = new StreamCollector(process.getInputStream());
            StreamCollector err = new StreamCollector(process.getErrorStream());
            out.start();
            err.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return CommandResult.failure("git 命令超时");
            }
            out.join();
            err.join();
            return new CommandResult(process.exitValue(), out.text(), err.text(), null, false);
        } catch (IOException e) {
            return new CommandResult(-1, "", "", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return CommandResult.failure("git 命令被中断");
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }

    private static final class StreamCollector extends Thread {

        private final InputStream input;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    if (buffer.size() < MAX_OUTPUT_BYTES) {
                        buffer.write(chunk, 0, Math.min(read, MAX_OUTPUT_BYTES - buffer.size()));
                    }
                }
            } catch (IOException ignored) {
                // command output is best effort
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        private String text() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class CommandResult {

        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final String error;
        private final boolean failedToStart;

        private CommandResult(int exitCode, String stdout, String stderr, String error,
                              boolean failedToStart) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.error = error;
            this.failedToStart = failedToStart;
        }

        private static CommandResult failure(String error) {
            return new CommandResult(-1, "", "", error, true);
        }
    }
}
