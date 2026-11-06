package com.flashfin.i18n.tool.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class MavenClasspathBuilder implements ModuleClasspathBuilder {

    private static final long TIMEOUT_SECONDS = 120L;
    private static final int ERROR_SNIPPET_LIMIT = 1500;

    private final String mavenCommand;

    public MavenClasspathBuilder() {
        this("mvn");
    }

    public MavenClasspathBuilder(String mavenCommand) {
        this.mavenCommand = mavenCommand;
    }

    @Override
    public ClasspathResult build(ResolvedModule module) throws ScanException {
        Path pom = module.getDomainRoot().resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED,
                    "模块 " + module.getName() + " 缺少 pom.xml: " + pom);
        }

        Path output;
        try {
            output = Files.createTempFile("flp-i18n-classpath-", ".txt");
        } catch (IOException e) {
            throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED, "无法创建临时 classpath 文件", e);
        }

        try {
            InvocationResult offline = invoke(pom, output, true);
            boolean online = false;
            InvocationResult chosen = offline;
            if (!offline.isSuccess()) {
                InvocationResult onlineResult = invoke(pom, output, false);
                if (onlineResult.isSuccess()) {
                    online = true;
                    chosen = onlineResult;
                }
            }
            if (!chosen.isSuccess()) {
                throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED,
                        "mvn dependency:build-classpath 失败（模块 " + module.getName() + "）: "
                                + chosen.describe());
            }
            if (!Files.isRegularFile(output)) {
                throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED,
                        "mvn 未生成 classpath 文件（模块 " + module.getName() + "）");
            }
            String content = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            List<Path> entries = parseClasspathContent(content);
            entries.add(0, module.getClassesDir());
            return new ClasspathResult(module.getClassesDir(), dedupe(entries), online);
        } catch (IOException e) {
            throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED,
                    "读取 classpath 失败（模块 " + module.getName() + "）", e);
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // temp file cleanup only
            }
        }
    }

    private InvocationResult invoke(Path pom, Path output, boolean offline) throws ScanException {
        List<String> command = new ArrayList<String>();
        command.add(mavenCommand);
        if (offline) {
            command.add("-o");
        }
        command.add("-q");
        command.add("-f");
        command.add(pom.toString());
        command.add("dependency:build-classpath");
        command.add("-Dmdep.outputFile=" + output.toString());
        command.add("-DincludeScope=runtime");

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED,
                    "无法启动 Maven（" + mavenCommand + "），请确认已安装并加入 PATH", e);
        }

        StringBuilder outputText = new StringBuilder();
        Thread reader = new Thread(new StreamPump(process.getInputStream(), outputText),
                "flp-i18n-mvn-output");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new InvocationResult(false, "超时 " + TIMEOUT_SECONDS + " 秒");
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            int exit = process.exitValue();
            return new InvocationResult(exit == 0, snippet(outputText.toString()));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ScanException(ScanException.CLASSPATH_BUILD_FAILED, "等待 Maven 时被中断", e);
        }
    }

    static List<Path> parseClasspathContent(String content) {
        List<Path> result = new ArrayList<Path>();
        if (content == null) {
            return result;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        Set<Path> seen = new LinkedHashSet<Path>();
        for (String part : trimmed.split(Pattern.quote(java.io.File.pathSeparator))) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            Path path = java.nio.file.Paths.get(value).toAbsolutePath().normalize();
            if (Files.exists(path) && seen.add(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private static List<Path> dedupe(List<Path> entries) {
        Set<Path> seen = new LinkedHashSet<Path>();
        for (Path entry : entries) {
            if (entry != null) {
                seen.add(entry.toAbsolutePath().normalize());
            }
        }
        return Collections.unmodifiableList(new ArrayList<Path>(seen));
    }

    private static String snippet(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() > ERROR_SNIPPET_LIMIT) {
            value = value.substring(value.length() - ERROR_SNIPPET_LIMIT);
        }
        return value;
    }

    private static final class InvocationResult {

        private final boolean success;
        private final String output;

        private InvocationResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }

        private boolean isSuccess() {
            return success;
        }

        private String describe() {
            return output.isEmpty() ? "无输出" : output;
        }
    }

    private static final class StreamPump implements Runnable {

        private final java.io.InputStream input;
        private final StringBuilder target;

        private StreamPump(java.io.InputStream input, StringBuilder target) {
            this.input = input;
            this.target = target;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (target) {
                        target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        if (target.length() > ERROR_SNIPPET_LIMIT * 4) {
                            target.delete(0, target.length() - ERROR_SNIPPET_LIMIT * 2);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process output is best-effort diagnostics only
            }
        }
    }
}
