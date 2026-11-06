package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.dictionary.DictionaryClient;
import com.flashfin.i18n.tool.dictionary.DictionaryClientFactory;
import com.flashfin.i18n.tool.dictionary.ModuleDictionaries;
import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
import com.flashfin.i18n.tool.git.GitGuard;
import com.flashfin.i18n.tool.git.GitStatus;
import com.flashfin.i18n.tool.journal.JournalException;
import com.flashfin.i18n.tool.journal.UploadJournal;
import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.messages.LocalMessagesException;
import com.flashfin.i18n.tool.messages.LocalMessagesLoader;
import com.flashfin.i18n.tool.platform.PlatformClient;
import com.flashfin.i18n.tool.platform.PlatformClientFactory;
import com.flashfin.i18n.tool.platform.PlatformSaveResult;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.MavenClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleScanResult;
import com.flashfin.i18n.tool.scan.ModuleScanner;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;
import com.flashfin.i18n.tool.security.TokenInspector;
import com.flashfin.i18n.tool.security.TokenState;
import com.flashfin.i18n.tool.sync.UploadEntry;
import com.flashfin.i18n.tool.sync.UploadPlan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class UploadCommand {

    private static final String COMMAND = "upload";
    private static final int DEFAULT_RATE_MS = 200;

    private final ModuleScanner moduleScanner;
    private final LocalMessagesLoader messagesLoader;
    private final DictionaryClientFactory dictionaryClientFactory;
    private final PlatformClientFactory platformClientFactory;
    private final GitGuard gitGuard;
    private final Map<String, String> env;

    public UploadCommand(ModuleScanner moduleScanner, LocalMessagesLoader messagesLoader,
                         DictionaryClientFactory dictionaryClientFactory,
                         PlatformClientFactory platformClientFactory, GitGuard gitGuard,
                         Map<String, String> env) {
        this.moduleScanner = moduleScanner;
        this.messagesLoader = messagesLoader;
        this.dictionaryClientFactory = dictionaryClientFactory;
        this.platformClientFactory = platformClientFactory;
        this.gitGuard = gitGuard;
        this.env = env;
    }

    public static UploadCommand defaults(Map<String, String> env) {
        return new UploadCommand(
                new ModuleScanner(new MavenClasspathBuilder(), new ClassScanner(),
                        new KeyGenerator(KeyRules.defaults())),
                new LocalMessagesLoader(),
                DictionaryClientFactory.http(),
                PlatformClientFactory.http(),
                new GitGuard(),
                env);
    }

    public CommandResult execute(CliArguments args) {
        ModuleCommandSupport.Setup setup = ModuleCommandSupport.prepare(args, env, COMMAND);
        if (setup.hasError()) {
            return setup.getError();
        }

        List<String> languages;
        try {
            languages = ModuleCommandSupport.selectedLanguages(args, setup.getConfig());
        } catch (CliException e) {
            return ModuleCommandSupport.failure(COMMAND, setup.getConfigPath(), "INVALID_ARGUMENT",
                    e.getMessage(), "配置中可用语言: " + setup.getConfig().getLanguages());
        }

        BuildResult build = buildPlan(setup, languages);
        if (!build.errors.isEmpty()) {
            return new CommandResult(build.exitCode, JsonEnvelope.failure(COMMAND, build.output,
                    build.warnings, build.errors.get(0)));
        }

        if (!args.isApply()) {
            build.output.put("apply", false);
            build.output.put("limit", args.getLimit());
            build.output.put("rateMs", rateMs(args));
            return new CommandResult(ExitCode.OK,
                    JsonEnvelope.success(COMMAND, build.output, build.warnings));
        }

        return apply(args, setup, languages, build);
    }

    private CommandResult apply(CliArguments args, ModuleCommandSupport.Setup setup,
                                List<String> languages, BuildResult build) {
        UploadPlan current = build.plan;
        build.output.put("apply", true);
        build.output.put("limit", args.getLimit());
        build.output.put("rateMs", rateMs(args));

        Path journalPath;
        UploadJournal journal;
        try {
            journal = UploadJournal.open(ModuleCommandSupport.toolDataDir(setup.getConfigPath()),
                    args.getPlanId());
            journalPath = journal.getFile();
        } catch (JournalException e) {
            return failure(build, ExitCode.WRITE_REJECTED, "JOURNAL_READ_FAILED",
                    e.getMessage(), "检查 .i18n-tool/journal 目录权限后重试");
        }

        List<UploadEntry> resumedEntries = new ArrayList<UploadEntry>(current.getEntries());
        Map<String, UploadEntry> seen = new LinkedHashMap<String, UploadEntry>();
        for (UploadEntry entry : resumedEntries) {
            seen.put(entry.id(), entry);
        }
        for (UploadEntry done : journal.completedEntries()) {
            if (!seen.containsKey(done.id())) {
                resumedEntries.add(done);
                seen.put(done.id(), done);
            }
        }
        UploadPlan resumed = UploadPlan.create(setup.getConfig().getPlatform().getSaveUrl(),
                languages, resumedEntries);
        if (!resumed.getPlanId().equalsIgnoreCase(args.getPlanId())) {
            build.output.put("requestedPlanId", args.getPlanId());
            build.output.put("computedPlanId", resumed.getPlanId());
            return failure(build, ExitCode.WRITE_REJECTED, "PLAN_STALE",
                    "计划已变化，planId 不匹配",
                    "重新运行不带 --apply 的 upload 生成新的 planId");
        }

        Map<String, UploadEntry> pendingMap = new LinkedHashMap<String, UploadEntry>();
        for (UploadEntry entry : resumed.getEntries()) {
            if (!journal.isCompleted(entry)) {
                pendingMap.put(entry.id(), entry);
            }
        }
        for (UploadEntry entry : current.getEntries()) {
            if (journal.isCompleted(entry)) {
                pendingMap.put(entry.id(), entry);
            }
        }
        List<UploadEntry> pending = new ArrayList<UploadEntry>(pendingMap.values());
        int completed = resumed.getEntries().size() - pending.size();
        int limited = applyLimit(pending, args.getLimit());
        build.output.put("alreadyCompleted", completed);
        build.output.put("pending", limited);
        build.output.put("journal", journalPath.toString());

        if (limited == 0) {
            build.output.put("applied", 0);
            build.output.put("failed", 0);
            build.output.put("remaining", 0);
            return new CommandResult(ExitCode.OK,
                    JsonEnvelope.success(COMMAND, build.output, build.warnings));
        }

        String token = env == null ? null : env.get(TokenInspector.ENV_TOKEN);
        if (token == null || token.trim().isEmpty()) {
            return failure(build, ExitCode.INVALID, "TOKEN_MISSING",
                    "未检测到环境变量 " + TokenInspector.ENV_TOKEN,
                    "export " + TokenInspector.ENV_TOKEN + "=<token> 后重试；Token 不得写入配置或日志");
        }
        token = token.trim();
        TokenState state = TokenInspector.inspect(token, Instant.now());
        if (state.isJwt() && state.getExpiresInSeconds() != null
                && state.getExpiresInSeconds().longValue() <= 0) {
            return failure(build, ExitCode.INVALID, "TOKEN_EXPIRED",
                    "Token 已过期", "重新获取 Token 后设置 " + TokenInspector.ENV_TOKEN);
        }

        String guardError = checkGit(build, pending, args.isForce(), build.warnings);
        if (guardError != null) {
            return failure(build, ExitCode.WRITE_REJECTED, GitStatus.GIT_DIRTY,
                    guardError, "提交目标仓库变更后重试，或使用 --force 明确跳过守卫");
        }

        PlatformClient client = platformClientFactory.create(
                setup.getConfig().getPlatform(), token);
        int rateMs = rateMs(args);
        int applied = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        UploadEntry failedEntry = null;
        PlatformSaveResult failedResult = null;

        for (int i = 0; i < limited; i++) {
            UploadEntry entry = pending.get(i);
            if (i > 0 && rateMs > 0 && !sleep(rateMs)) {
                failedResult = PlatformSaveResult.failure(0, "UPLOAD_INTERRUPTED",
                        "上传被中断", false);
                break;
            }
            PlatformSaveResult result = client.save(entry);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("module", entry.getModule());
            item.put("os", entry.getOs());
            item.put("key", entry.getKey());
            item.put("language", entry.getLanguage());
            item.put("ok", result.isOk());
            if (!result.isOk()) {
                item.put("status", result.getStatus());
                item.put("error", result.getMessage());
            }
            results.add(item);
            if (result.isOk()) {
                applied++;
                try {
                    journal.markCompleted(entry);
                } catch (JournalException e) {
                    build.output.put("applied", applied);
                    build.output.put("failed", 0);
                    build.output.put("remaining", limited - applied);
                    build.output.put("results", results);
                    return failure(build, ExitCode.WRITE_REJECTED,
                            "JOURNAL_WRITE_FAILED", e.getMessage(),
                            "上传已成功但进度未记录；检查 .i18n-tool/journal 目录权限");
                }
            } else {
                failed++;
                failedEntry = entry;
                failedResult = result;
                break;
            }
        }

        build.output.put("applied", applied);
        build.output.put("failed", failed);
        build.output.put("remaining", limited - applied - failed);
        build.output.put("results", results);

        if (failedResult != null) {
            int exitCode = "INVALID_LANGUAGE".equals(failedResult.getCode())
                    ? ExitCode.INVALID : ExitCode.REMOTE_ERROR;
            return failure(build, exitCode, failedResult.getCode(),
                    failedResult.getMessage() + (failedEntry == null ? ""
                            : "，key=" + failedEntry.getKey()
                            + "，language=" + failedEntry.getLanguage()),
                    failedResult.isRetryable()
                            ? "稍后重试同一 planId，journal 会跳过已完成的 key"
                            : "检查 Token 权限、语言映射与平台状态后重试");
        }
        return new CommandResult(ExitCode.OK,
                JsonEnvelope.success(COMMAND, build.output, build.warnings));
    }

    private String checkGit(BuildResult build, List<UploadEntry> pending, boolean force,
                            List<String> warnings) {
        List<String> modules = new ArrayList<String>();
        for (UploadEntry entry : pending) {
            if (!modules.contains(entry.getModule())) {
                modules.add(entry.getModule());
            }
        }
        for (String module : modules) {
            ResolvedModule resolved = build.modules.get(module);
            if (resolved == null) {
                continue;
            }
            GitStatus status = gitGuard.check(resolved.getRepoRoot());
            if (status.isClean()) {
                continue;
            }
            if (!force) {
                return status.getMessage() + "，module=" + module
                        + "，repo=" + resolved.getRepoRoot();
            }
            String warning = "GIT_FORCE_OVERRIDE: module=" + module
                    + "，repo=" + resolved.getRepoRoot() + "，变更 "
                    + status.getEntries().size() + " 项";
            warnings.add(warning);
            System.err.println("WARN: " + warning);
        }
        return null;
    }

    private BuildResult buildPlan(ModuleCommandSupport.Setup setup, List<String> languages) {
        BuildResult build = new BuildResult();
        DictionaryClient client = dictionaryClientFactory.create(setup.getConfig().getPlatform());
        Map<String, RemoteDictionary> cache = new LinkedHashMap<String, RemoteDictionary>();
        List<UploadEntry> entries = new ArrayList<UploadEntry>();

        for (ModuleConfig moduleConfig : setup.getSelected()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("module", moduleConfig.getName());
            data.put("os", moduleConfig.getOs());
            data.put("packages", moduleConfig.getPackages());
            try {
                ResolvedModule module = setup.getRegistry().resolve(moduleConfig);
                build.modules.put(moduleConfig.getName(), module);
                data.put("repoRoot", module.getRepoRoot().toString());
                data.put("domainPath", module.getDomainRoot().toString());
                ModuleScanResult scanned = moduleScanner.scan(module);
                data.put("classesScanned", scanned.getClassesScanned());
                data.put("counts", ModuleCommandSupport.counts(scanned.getKeys()));
                if (!scanned.getClassLoadFailures().isEmpty()) {
                    build.warnings.add("CLASS_LOAD_FAILED: 模块 " + moduleConfig.getName()
                            + " 有 " + scanned.getClassLoadFailures().size() + " 个类无法加载，已跳过");
                }

                Map<String, LocalMessages> local = loadLocal(module, languages, build.warnings);
                RemoteDictionaries remote = fetchRemote(client, moduleConfig, languages,
                        scanned, cache);
                if (remote.error != null) {
                    data.put("ok", false);
                    data.put("error", ModuleCommandSupport.errorData(
                            "REMOTE_UNAVAILABLE", remote.error,
                            "检查 CDN 可达性、os 与语言代码；恢复后重试"));
                    build.moduleData.add(data);
                    build.errors.add(new ErrorInfo("REMOTE_UNAVAILABLE", remote.error,
                            "检查 CDN 可达性、os 与语言代码；恢复后重试", true));
                    build.exitCode = ExitCode.REMOTE_ERROR;
                    continue;
                }

                int moduleEntries = 0;
                int skippedBlank = 0;
                for (String language : languages) {
                    LocalMessages messages = local.get(language);
                    RemoteDictionary code = remote.code.get(language);
                    RemoteDictionary enums = remote.enums.get(language);
                    for (String key : new TreeSet<String>(scanned.getKeys().getCodeKeys().keySet())) {
                        if (!ModuleCommandSupport.isBlank(code.getValues().get(key))) {
                            continue;
                        }
                        String value = messages.get(key);
                        if (ModuleCommandSupport.isBlank(value)) {
                            skippedBlank++;
                            continue;
                        }
                        entries.add(new UploadEntry(moduleConfig.getName(), moduleConfig.getOs(),
                                key, "code", language, value));
                        moduleEntries++;
                    }
                    for (String key : new TreeSet<String>(scanned.getKeys().getEnumKeys().keySet())) {
                        if (!ModuleCommandSupport.isBlank(enums.getValues().get(key))) {
                            continue;
                        }
                        String value = messages.get(key);
                        if (ModuleCommandSupport.isBlank(value)) {
                            skippedBlank++;
                            continue;
                        }
                        entries.add(new UploadEntry(moduleConfig.getName(), remote.enumOs,
                                key, "enum", language, value));
                        moduleEntries++;
                    }
                }
                data.put("ok", true);
                data.put("pendingEntries", moduleEntries);
                data.put("skippedBlankValues", skippedBlank);
                build.moduleData.add(data);
            } catch (ScanException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode())));
                build.moduleData.add(data);
                build.errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode()), false));
                build.exitCode = ExitCode.INVALID;
            } catch (LocalMessagesException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件"));
                build.moduleData.add(data);
                build.errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件", false));
                build.exitCode = ExitCode.INVALID;
            }
        }

        build.plan = UploadPlan.create(setup.getConfig().getPlatform().getSaveUrl(),
                languages, entries);
        build.output.put("configPath", setup.getConfigPath().toString());
        build.output.put("workspaceRoot", setup.getConfig().getWorkspaceRoot().toString());
        build.output.put("languages", languages);
        build.output.put("modules", build.moduleData);
        build.output.put("planId", build.plan.getPlanId());
        build.output.put("plan", build.plan.toOutput());
        return build;
    }

    private Map<String, LocalMessages> loadLocal(ResolvedModule module, List<String> languages,
                                                 List<String> warnings)
            throws LocalMessagesException {
        Map<String, LocalMessages> result = new LinkedHashMap<String, LocalMessages>();
        for (String language : languages) {
            LocalMessages messages = messagesLoader.load(module, language);
            result.put(language, messages);
            if (!messages.getMissingFiles().isEmpty()) {
                warnings.add("LOCAL_MESSAGES_MISSING: 模块 " + module.getName() + " 语言 "
                        + language + " 有 " + messages.getMissingFiles().size()
                        + " 个 properties 文件缺失");
            }
        }
        return result;
    }

    private RemoteDictionaries fetchRemote(DictionaryClient client, ModuleConfig moduleConfig,
                                           List<String> languages, ModuleScanResult scanned,
                                           Map<String, RemoteDictionary> cache) {
        String enumOs = ModuleDictionaries.enumOs(moduleConfig.getOs());
        boolean needsEnum = !scanned.getKeys().getEnumKeys().isEmpty();
        boolean needsCode = !scanned.getKeys().getCodeKeys().isEmpty();
        RemoteDictionaries result = new RemoteDictionaries(enumOs);

        for (String language : languages) {
            RemoteDictionary code = fetch(client, moduleConfig.getOs(), language, cache);
            RemoteDictionary enums = enumOs.equals(moduleConfig.getOs())
                    ? code : fetch(client, enumOs, language, cache);
            if ((needsCode && !code.isAvailable()) || (needsEnum && !enums.isAvailable())) {
                RemoteDictionary failed = !code.isAvailable() ? code : enums;
                result.error = "模块 " + moduleConfig.getName() + " 语言 " + language
                        + " 字典不可用: " + failed.getErrorMessage();
                return result;
            }
            result.code.put(language, code);
            result.enums.put(language, enums);
        }
        return result;
    }

    private RemoteDictionary fetch(DictionaryClient client, String os, String language,
                                   Map<String, RemoteDictionary> cache) {
        String key = os + "|" + language;
        RemoteDictionary cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        RemoteDictionary fetched = client.fetch(os, language);
        cache.put(key, fetched);
        return fetched;
    }

    private int rateMs(CliArguments args) {
        return args.getRateMs() == null ? DEFAULT_RATE_MS : args.getRateMs().intValue();
    }

    private int applyLimit(List<UploadEntry> pending, Integer limit) {
        if (limit == null || limit.intValue() >= pending.size()) {
            return pending.size();
        }
        return limit.intValue();
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private CommandResult failure(BuildResult build, int exitCode, String code, String message,
                                  String hint) {
        return new CommandResult(exitCode, JsonEnvelope.failure(COMMAND, build.output,
                build.warnings, new ErrorInfo(code, message, hint, false)));
    }

    private static final class BuildResult {

        private UploadPlan plan;
        private final List<Map<String, Object>> moduleData = new ArrayList<Map<String, Object>>();
        private final Map<String, ResolvedModule> modules =
                new LinkedHashMap<String, ResolvedModule>();
        private final Map<String, Object> output = new LinkedHashMap<String, Object>();
        private final List<String> warnings = new ArrayList<String>();
        private final List<ErrorInfo> errors = new ArrayList<ErrorInfo>();
        private int exitCode = ExitCode.OK;
    }

    private static final class RemoteDictionaries {

        private final String enumOs;
        private final Map<String, RemoteDictionary> code =
                new LinkedHashMap<String, RemoteDictionary>();
        private final Map<String, RemoteDictionary> enums =
                new LinkedHashMap<String, RemoteDictionary>();
        private String error;

        private RemoteDictionaries(String enumOs) {
            this.enumOs = enumOs;
        }
    }
}
