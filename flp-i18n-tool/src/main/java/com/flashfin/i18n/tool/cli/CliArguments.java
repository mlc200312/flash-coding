package com.flashfin.i18n.tool.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CliArguments {

    private final String command;
    private final Path configPath;
    private final String module;
    private final String os;
    private final List<String> languages;
    private final boolean help;
    private final boolean apply;
    private final String planId;
    private final Integer limit;
    private final Integer rateMs;
    private final boolean force;

    private CliArguments(String command, Path configPath, String module, String os,
                         List<String> languages, boolean help, boolean apply, String planId,
                         Integer limit, Integer rateMs, boolean force) {
        this.command = command;
        this.configPath = configPath;
        this.module = module;
        this.os = os;
        this.languages = Collections.unmodifiableList(new ArrayList<String>(languages));
        this.help = help;
        this.apply = apply;
        this.planId = planId;
        this.limit = limit;
        this.rateMs = rateMs;
        this.force = force;
    }

    public static CliArguments parse(String[] args) throws CliException {
        String command = null;
        Path configPath = null;
        String module = null;
        String os = null;
        List<String> languages = null;
        boolean help = false;
        boolean apply = false;
        boolean force = false;
        String planId = null;
        Integer limit = null;
        Integer rateMs = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--json".equals(arg)) {
                    continue;
                } else if ("--config".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--config 缺少路径");
                    }
                    if (configPath != null) {
                        throw new CliException("--config 只能指定一次");
                    }
                    configPath = Paths.get(args[++i].trim());
                } else if (arg.startsWith("--config=")) {
                    if (configPath != null) {
                        throw new CliException("--config 只能指定一次");
                    }
                    configPath = Paths.get(requiredValue(arg, "--config="));
                } else if ("--module".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--module 缺少模块名");
                    }
                    if (module != null) {
                        throw new CliException("--module 只能指定一次");
                    }
                    module = args[++i].trim();
                } else if (arg.startsWith("--module=")) {
                    if (module != null) {
                        throw new CliException("--module 只能指定一次");
                    }
                    module = requiredValue(arg, "--module=");
                } else if ("--os".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--os 缺少取值");
                    }
                    if (os != null) {
                        throw new CliException("--os 只能指定一次");
                    }
                    os = args[++i].trim();
                } else if (arg.startsWith("--os=")) {
                    if (os != null) {
                        throw new CliException("--os 只能指定一次");
                    }
                    os = requiredValue(arg, "--os=");
                } else if ("--apply".equals(arg)) {
                    apply = true;
                } else if ("--force".equals(arg)) {
                    force = true;
                } else if ("--plan-id".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--plan-id 缺少取值");
                    }
                    if (planId != null) {
                        throw new CliException("--plan-id 只能指定一次");
                    }
                    planId = args[++i].trim();
                } else if (arg.startsWith("--plan-id=")) {
                    if (planId != null) {
                        throw new CliException("--plan-id 只能指定一次");
                    }
                    planId = requiredValue(arg, "--plan-id=");
                } else if ("--limit".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--limit 缺少取值");
                    }
                    if (limit != null) {
                        throw new CliException("--limit 只能指定一次");
                    }
                    limit = parseLimit(args[++i]);
                } else if (arg.startsWith("--limit=")) {
                    if (limit != null) {
                        throw new CliException("--limit 只能指定一次");
                    }
                    limit = parseLimit(requiredValue(arg, "--limit="));
                } else if ("--rate-ms".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--rate-ms 缺少取值");
                    }
                    if (rateMs != null) {
                        throw new CliException("--rate-ms 只能指定一次");
                    }
                    rateMs = parseRate(args[++i]);
                } else if (arg.startsWith("--rate-ms=")) {
                    if (rateMs != null) {
                        throw new CliException("--rate-ms 只能指定一次");
                    }
                    rateMs = parseRate(requiredValue(arg, "--rate-ms="));
                } else if ("--languages".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].trim().isEmpty()) {
                        throw new CliException("--languages 缺少语言列表");
                    }
                    if (languages != null) {
                        throw new CliException("--languages 只能指定一次");
                    }
                    languages = parseLanguages(args[++i]);
                } else if (arg.startsWith("--languages=")) {
                    if (languages != null) {
                        throw new CliException("--languages 只能指定一次");
                    }
                    languages = parseLanguages(requiredValue(arg, "--languages="));
                } else if (arg.startsWith("-")) {
                    throw new CliException("未知参数: " + arg);
                } else if (command == null) {
                    command = arg;
                } else {
                    throw new CliException("多余参数: " + arg);
                }
            }
        }

        if (!help && command == null) {
            throw new CliException("缺少命令，可用命令: status, scan, diff, validate, upload, download");
        }
        if (apply && planId == null) {
            throw new CliException("--apply 需要 --plan-id；先运行不带 --apply 的命令生成计划");
        }
        if (!apply && planId != null) {
            throw new CliException("--plan-id 只能与 --apply 同时使用");
        }
        if (planId != null && !planId.matches("[0-9a-fA-F]{64}")) {
            throw new CliException("--plan-id 必须是 64 位十六进制 SHA-256");
        }
        return new CliArguments(command, configPath, module, os,
                languages == null ? Collections.<String>emptyList() : languages, help,
                apply, planId, limit, rateMs, force);
    }

    private static Integer parseLimit(String raw) throws CliException {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new CliException("--limit 必须大于 0");
            }
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new CliException("--limit 必须是整数: " + raw);
        }
    }

    private static Integer parseRate(String raw) throws CliException {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new CliException("--rate-ms 不得为负数");
            }
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new CliException("--rate-ms 必须是整数: " + raw);
        }
    }

    private static List<String> parseLanguages(String raw) throws CliException {
        List<String> result = new ArrayList<String>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw new CliException("--languages 包含空语言");
            }
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        if (result.isEmpty()) {
            throw new CliException("--languages 缺少语言列表");
        }
        return result;
    }

    private static String requiredValue(String arg, String prefix) throws CliException {
        String value = arg.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new CliException(prefix + " 缺少取值");
        }
        return value;
    }

    public String getCommand() {
        return command;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public String getModule() {
        return module;
    }

    public String getOs() {
        return os;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public boolean isHelp() {
        return help;
    }

    public boolean isApply() {
        return apply;
    }

    public String getPlanId() {
        return planId;
    }

    public Integer getLimit() {
        return limit;
    }

    public Integer getRateMs() {
        return rateMs;
    }

    public boolean isForce() {
        return force;
    }
}
