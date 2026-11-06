package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.dictionary.DictionaryClient;
import com.flashfin.i18n.tool.dictionary.DictionaryClientFactory;
import com.flashfin.i18n.tool.dictionary.ModuleDictionaries;
import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
import com.flashfin.i18n.tool.diff.DiffEngine;
import com.flashfin.i18n.tool.diff.DiffEntry;
import com.flashfin.i18n.tool.diff.DiffResult;
import com.flashfin.i18n.tool.diff.LanguageDiff;
import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.messages.LocalMessagesException;
import com.flashfin.i18n.tool.messages.LocalMessagesLoader;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.GeneratedKeys;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.MavenClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleScanResult;
import com.flashfin.i18n.tool.scan.ModuleScanner;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class DiffCommand {

    private static final String COMMAND = "diff";
    private static final String REMOTE_UNAVAILABLE = "REMOTE_UNAVAILABLE";

    private final ModuleScanner moduleScanner;
    private final LocalMessagesLoader messagesLoader;
    private final DictionaryClientFactory dictionaryClientFactory;
    private final DiffEngine diffEngine;
    private final Map<String, String> env;

    public DiffCommand(ModuleScanner moduleScanner, LocalMessagesLoader messagesLoader,
                       DictionaryClientFactory dictionaryClientFactory, DiffEngine diffEngine,
                       Map<String, String> env) {
        this.moduleScanner = moduleScanner;
        this.messagesLoader = messagesLoader;
        this.dictionaryClientFactory = dictionaryClientFactory;
        this.diffEngine = diffEngine;
        this.env = env;
    }

    public static DiffCommand defaults(Map<String, String> env) {
        return new DiffCommand(
                new ModuleScanner(new MavenClasspathBuilder(), new ClassScanner(),
                        new KeyGenerator(KeyRules.defaults())),
                new LocalMessagesLoader(),
                DictionaryClientFactory.http(),
                new DiffEngine(),
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

        DictionaryClient client = dictionaryClientFactory.create(setup.getConfig().getPlatform());
        Map<String, RemoteDictionary> dictionaryCache = new LinkedHashMap<String, RemoteDictionary>();
        Map<String, TreeSet<String>> missingOnPlatform = languageKeySets(languages);
        Map<String, TreeSet<String>> missingLocally = languageKeySets(languages);
        List<Map<String, Object>> zhConflicts = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> remoteUnavailable = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> moduleData = new ArrayList<Map<String, Object>>();
        List<String> warnings = new ArrayList<String>();
        List<ErrorInfo> errors = new ArrayList<ErrorInfo>();
        int missingOnPlatformTotal = 0;
        int missingLocallyTotal = 0;
        int zhConflictsTotal = 0;

        for (ModuleConfig moduleConfig : setup.getSelected()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("module", moduleConfig.getName());
            data.put("os", moduleConfig.getOs());
            data.put("packages", moduleConfig.getPackages());

            ResolvedModule module;
            try {
                module = setup.getRegistry().resolve(moduleConfig);
                data.put("repoRoot", module.getRepoRoot().toString());
                data.put("domainPath", module.getDomainRoot().toString());
                ModuleScanResult scanned = moduleScanner.scan(module);
                data.put("classesScanned", scanned.getClassesScanned());
                data.put("counts", ModuleCommandSupport.counts(scanned.getKeys()));
                addClassLoadWarning(scanned, warnings);

                Map<String, LocalMessages> local = loadLocal(module, languages, warnings);
                Map<String, RemoteDictionary> remote = fetchRemote(client, moduleConfig,
                        languages, scanned.getKeys(), dictionaryCache);

                int available = 0;
                for (RemoteDictionary dictionary : remote.values()) {
                    if (dictionary.isAvailable()) {
                        available++;
                    } else {
                        remoteUnavailable.add(remoteEntry(moduleConfig, dictionary));
                    }
                }

                if (available == 0) {
                    data.put("ok", false);
                    data.put("error", ModuleCommandSupport.errorData(REMOTE_UNAVAILABLE,
                            "模块 " + moduleConfig.getName() + " 的所有语言字典均拉取失败",
                            "检查 CDN 可达性、os 与语言代码；恢复后重试"));
                    data.put("remoteUnavailable", remoteEntries(moduleConfig, remote));
                    moduleData.add(data);
                    errors.add(new ErrorInfo(REMOTE_UNAVAILABLE,
                            "模块 " + moduleConfig.getName() + " 的所有语言字典均拉取失败",
                            "检查 CDN 可达性、os 与语言代码；恢复后重试", true));
                    continue;
                }

                DiffResult diff = diffEngine.diff(scanned.getKeys(), local, remote);
                data.put("ok", true);
                data.put("languages", languageData(diff));
                data.put("totals", diffTotals(diff.getMissingOnPlatformTotal(),
                        diff.getMissingLocallyTotal(), diff.getZhConflictsTotal(),
                        diff.getRemoteUnavailable().size()));
                data.put("remoteUnavailable", remoteEntries(moduleConfig, remote));
                moduleData.add(data);

                mergeKeys(missingOnPlatform, diff, true);
                mergeKeys(missingLocally, diff, false);
                mergeConflicts(zhConflicts, moduleConfig.getName(), diff);
                missingOnPlatformTotal += diff.getMissingOnPlatformTotal();
                missingLocallyTotal += diff.getMissingLocallyTotal();
                zhConflictsTotal += diff.getZhConflictsTotal();

                if (available < remote.size()) {
                    warnings.add("REMOTE_PARTIAL: 模块 " + moduleConfig.getName() + " 有 "
                            + (remote.size() - available) + " 种语言字典不可用");
                }
            } catch (ScanException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode())));
                moduleData.add(data);
                errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode()), false));
            } catch (LocalMessagesException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件"));
                moduleData.add(data);
                errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件", false));
            }
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configPath", setup.getConfigPath().toString());
        root.put("workspaceRoot", setup.getConfig().getWorkspaceRoot().toString());
        root.put("languages", languages);
        root.put("moduleCount", moduleData.size());
        root.put("totals", diffTotals(missingOnPlatformTotal, missingLocallyTotal,
                zhConflictsTotal, remoteUnavailable.size()));
        root.put("missingOnPlatform", keyLists(missingOnPlatform));
        root.put("missingLocally", keyLists(missingLocally));
        root.put("zhConflicts", zhConflicts);
        root.put("remoteUnavailable", remoteUnavailable);
        root.put("modules", moduleData);

        if (!errors.isEmpty()) {
            return new CommandResult(exitCode(errors),
                    JsonEnvelope.failure(COMMAND, root, warnings, errors.get(0)));
        }
        return new CommandResult(ExitCode.OK, JsonEnvelope.success(COMMAND, root, warnings));
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

    private Map<String, RemoteDictionary> fetchRemote(DictionaryClient client,
                                                     ModuleConfig moduleConfig,
                                                     List<String> languages,
                                                     GeneratedKeys keys,
                                                     Map<String, RemoteDictionary> cache) {
        String enumOs = ModuleDictionaries.enumOs(moduleConfig.getOs());
        boolean needsEnumDictionary = !keys.getEnumKeys().isEmpty() && !enumOs.equals(moduleConfig.getOs());
        Map<String, RemoteDictionary> result = new LinkedHashMap<String, RemoteDictionary>();
        for (String language : languages) {
            RemoteDictionary code = fetch(client, moduleConfig.getOs(), language, cache);
            if (!needsEnumDictionary) {
                result.put(language, code);
                continue;
            }
            RemoteDictionary enums = fetch(client, enumOs, language, cache);
            result.put(language, ModuleDictionaries.merge(language, moduleConfig.getOs(), code,
                    enumOs, enums));
        }
        return result;
    }

    private RemoteDictionary fetch(DictionaryClient client, String os, String language,
                                   Map<String, RemoteDictionary> cache) {
        String cacheKey = os + "|" + language;
        RemoteDictionary cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        RemoteDictionary fetched = client.fetch(os, language);
        cache.put(cacheKey, fetched);
        return fetched;
    }

    private Map<String, Object> languageData(DiffResult diff) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (LanguageDiff languageDiff : diff.getLanguages().values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("remoteAvailable", languageDiff.isRemoteAvailable());
            item.put("counts", diffTotals(languageDiff.getMissingOnPlatform().size(),
                    languageDiff.getMissingLocally().size(),
                    languageDiff.getZhConflicts().size(), 0));
            item.put("missingOnPlatform", entries(languageDiff.getMissingOnPlatform()));
            item.put("missingLocally", entries(languageDiff.getMissingLocally()));
            item.put("zhConflicts", entries(languageDiff.getZhConflicts()));
            result.put(languageDiff.getLanguage(), item);
        }
        return result;
    }

    private List<Map<String, Object>> entries(List<DiffEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (DiffEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", entry.getKey());
            item.put("type", entry.getType());
            item.put("localValue", entry.getLocalValue());
            item.put("remoteValue", entry.getRemoteValue());
            result.add(item);
        }
        return result;
    }

    private void mergeKeys(Map<String, TreeSet<String>> target, DiffResult diff,
                           boolean missingOnPlatform) {
        for (LanguageDiff languageDiff : diff.getLanguages().values()) {
            TreeSet<String> keys = target.get(languageDiff.getLanguage());
            if (keys == null) {
                continue;
            }
            List<DiffEntry> entries = missingOnPlatform
                    ? languageDiff.getMissingOnPlatform()
                    : languageDiff.getMissingLocally();
            for (DiffEntry entry : entries) {
                keys.add(entry.getKey());
            }
        }
    }

    private void mergeConflicts(List<Map<String, Object>> target, String module, DiffResult diff) {
        for (LanguageDiff languageDiff : diff.getLanguages().values()) {
            for (DiffEntry entry : languageDiff.getZhConflicts()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("module", module);
                item.put("language", languageDiff.getLanguage());
                item.put("key", entry.getKey());
                item.put("type", entry.getType());
                item.put("localValue", entry.getLocalValue());
                item.put("remoteValue", entry.getRemoteValue());
                target.add(item);
            }
        }
    }

    private List<Map<String, Object>> remoteEntries(ModuleConfig moduleConfig,
                                                    Map<String, RemoteDictionary> remote) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RemoteDictionary dictionary : remote.values()) {
            if (!dictionary.isAvailable()) {
                result.add(remoteEntry(moduleConfig, dictionary));
            }
        }
        return result;
    }

    private Map<String, Object> remoteEntry(ModuleConfig moduleConfig,
                                            RemoteDictionary dictionary) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("module", moduleConfig.getName());
        item.put("language", dictionary.getLanguage());
        item.put("url", dictionary.getUrl());
        item.put("code", dictionary.getErrorCode());
        item.put("message", dictionary.getErrorMessage());
        return item;
    }

    private Map<String, TreeSet<String>> languageKeySets(List<String> languages) {
        Map<String, TreeSet<String>> result = new LinkedHashMap<String, TreeSet<String>>();
        for (String language : languages) {
            result.put(language, new TreeSet<String>());
        }
        return result;
    }

    private Map<String, List<String>> keyLists(Map<String, TreeSet<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, TreeSet<String>> entry : source.entrySet()) {
            result.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> diffTotals(int missingOnPlatform, int missingLocally,
                                           int zhConflicts, int remoteUnavailable) {
        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("missingOnPlatform", missingOnPlatform);
        totals.put("missingLocally", missingLocally);
        totals.put("zhConflicts", zhConflicts);
        totals.put("remoteUnavailable", remoteUnavailable);
        return totals;
    }

    private void addClassLoadWarning(ModuleScanResult scanned, List<String> warnings) {
        if (!scanned.getClassLoadFailures().isEmpty()) {
            warnings.add("CLASS_LOAD_FAILED: 模块 " + scanned.getModule().getName() + " 有 "
                    + scanned.getClassLoadFailures().size() + " 个类无法加载，已跳过");
        }
    }

    private int exitCode(List<ErrorInfo> errors) {
        for (ErrorInfo error : errors) {
            if (REMOTE_UNAVAILABLE.equals(error.getCode())) {
                return ExitCode.REMOTE_ERROR;
            }
        }
        return ExitCode.INVALID;
    }
}
