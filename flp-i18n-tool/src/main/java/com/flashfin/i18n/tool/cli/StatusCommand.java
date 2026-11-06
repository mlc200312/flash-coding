package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ConfigException;
import com.flashfin.i18n.tool.config.ModulesConfig;
import com.flashfin.i18n.tool.config.ModulesConfigLoader;
import com.flashfin.i18n.tool.platform.HttpReachabilityProbe;
import com.flashfin.i18n.tool.platform.ProbeResult;
import com.flashfin.i18n.tool.platform.ReachabilityProbe;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.security.TokenInspector;
import com.flashfin.i18n.tool.security.TokenState;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StatusCommand {

    public static final String ENV_CONFIG = "FLP_I18N_CONFIG";
    private static final String COMMAND = "status";
    private static final int PROBE_TIMEOUT_CAP_MS = 5000;
    private static final long EXPIRING_SOON_SECONDS = 86400L;

    private final ModulesConfigLoader loader;
    private final ReachabilityProbe probe;
    private final Map<String, String> env;

    public StatusCommand(ModulesConfigLoader loader, ReachabilityProbe probe, Map<String, String> env) {
        this.loader = loader;
        this.probe = probe;
        this.env = env;
    }

    public static StatusCommand defaults(Map<String, String> env) {
        return new StatusCommand(new ModulesConfigLoader(), new HttpReachabilityProbe(), env);
    }

    public CommandResult execute(CliArguments args) {
        Path configPath = resolveConfigPath(args).toAbsolutePath().normalize();

        ModulesConfig config;
        try {
            config = loader.load(configPath);
        } catch (ConfigException e) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("configOk", false);
            data.put("configPath", configPath.toString());
            ErrorInfo error = new ErrorInfo(e.getCode(), e.getMessage(), configHint(e.getCode()), false);
            return new CommandResult(ExitCode.INVALID, JsonEnvelope.failure(COMMAND, data, error));
        }

        Instant now = Instant.now();
        TokenState token = TokenInspector.inspect(env == null ? null : env.get(TokenInspector.ENV_TOKEN), now);

        int probeTimeoutMs = Math.max(100,
                Math.min(config.getPlatform().getTimeoutMs(), PROBE_TIMEOUT_CAP_MS));
        ProbeResult cdnProbe = safeProbe(config.getPlatform().getDictionaryBase(), probeTimeoutMs);
        ProbeResult platformProbe = safeProbe(config.getPlatform().getSaveUrl(), probeTimeoutMs);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("configOk", true);
        data.put("configPath", configPath.toString());
        data.put("version", config.getVersion());
        data.put("workspaceRoot", config.getWorkspaceRoot().toString());
        data.put("moduleCount", config.getModules().size());
        data.put("modules", new ArrayList<String>(config.getModules().keySet()));
        data.put("languages", config.getLanguages());
        data.put("token", tokenData(token));
        data.put("remote", remoteData(config.getPlatform().getDictionaryBase(),
                config.getPlatform().getSaveUrl(), probeTimeoutMs, cdnProbe, platformProbe));

        List<String> warnings = new ArrayList<String>();
        ErrorInfo error = null;
        int exitCode = ExitCode.OK;

        if (!token.isPresent()) {
            error = new ErrorInfo("TOKEN_MISSING", "未检测到环境变量 " + TokenInspector.ENV_TOKEN,
                    "export " + TokenInspector.ENV_TOKEN + "=<token> 后重试；Token 不得写入配置文件、日志或提交记录",
                    false);
            exitCode = ExitCode.INVALID;
        } else if (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now)) {
            error = new ErrorInfo("TOKEN_EXPIRED", "Token 已过期: exp=" + token.getExpiresAt(),
                    "更新 " + TokenInspector.ENV_TOKEN + " 后重试", false);
            exitCode = ExitCode.INVALID;
        } else if (token.getParseError() != null) {
            warnings.add("TOKEN_UNVERIFIABLE: " + token.getParseError());
        } else if (token.getExpiresInSeconds() != null
                && token.getExpiresInSeconds().longValue() < EXPIRING_SOON_SECONDS) {
            warnings.add("TOKEN_EXPIRING_SOON: 剩余 " + token.getExpiresInSeconds() + " 秒");
        }

        boolean cdnReachable = cdnProbe.isReachable();
        boolean platformReachable = platformProbe.isReachable();
        if (error == null && (!cdnReachable || !platformReachable)) {
            error = remoteError(config.getPlatform().getDictionaryBase(),
                    config.getPlatform().getSaveUrl(), cdnProbe, platformProbe);
            exitCode = ExitCode.REMOTE_ERROR;
        } else if (error != null) {
            if (!cdnReachable) {
                warnings.add("CDN_UNREACHABLE: " + probeDetail(config.getPlatform().getDictionaryBase(), cdnProbe));
            }
            if (!platformReachable) {
                warnings.add("PLATFORM_UNREACHABLE: " + probeDetail(config.getPlatform().getSaveUrl(), platformProbe));
            }
        }

        JsonEnvelope envelope = error == null
                ? JsonEnvelope.success(COMMAND, data, warnings)
                : JsonEnvelope.failure(COMMAND, data, warnings, error);
        return new CommandResult(exitCode, envelope);
    }

    private Path resolveConfigPath(CliArguments args) {
        if (args != null && args.getConfigPath() != null) {
            return args.getConfigPath();
        }
        String fromEnv = env == null ? null : env.get(ENV_CONFIG);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return Paths.get(fromEnv.trim());
        }
        return Paths.get("modules.yaml");
    }

    private ProbeResult safeProbe(String url, int timeoutMs) {
        try {
            return probe.probe(url, timeoutMs);
        } catch (RuntimeException e) {
            return new ProbeResult(false, null, 0L, "probe failed: " + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> tokenData(TokenState token) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("present", token.isPresent());
        data.put("jwt", token.isJwt());
        data.put("expiresAt", token.getExpiresAt() == null ? null : token.getExpiresAt().toString());
        data.put("expiresInSeconds", token.getExpiresInSeconds());
        if (token.getParseError() != null) {
            data.put("parseError", token.getParseError());
        }
        return data;
    }

    private Map<String, Object> remoteData(String cdnUrl, String platformUrl, int timeoutMs,
                                           ProbeResult cdn, ProbeResult platform) {
        Map<String, Object> remote = new LinkedHashMap<String, Object>();
        remote.put("timeoutMs", timeoutMs);
        remote.put("cdn", probeData(cdnUrl, cdn));
        remote.put("platform", probeData(platformUrl, platform));
        return remote;
    }

    private Map<String, Object> probeData(String url, ProbeResult result) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("url", url);
        data.put("reachable", result.isReachable());
        data.put("status", result.getStatusCode());
        data.put("latencyMs", result.getLatencyMs());
        data.put("error", result.getError());
        return data;
    }

    private ErrorInfo remoteError(String cdnUrl, String platformUrl,
                                  ProbeResult cdn, ProbeResult platform) {
        String hint = "检查网络、代理或防火墙；可临时用 --config 指向测试环境配置";
        if (!cdn.isReachable() && !platform.isReachable()) {
            return new ErrorInfo("REMOTE_UNREACHABLE",
                    "CDN 与翻译平台均不可达；CDN=" + probeDetail(cdnUrl, cdn)
                            + "；platform=" + probeDetail(platformUrl, platform), hint, true);
        }
        if (!cdn.isReachable()) {
            return new ErrorInfo("CDN_UNREACHABLE", "CDN 不可达: " + probeDetail(cdnUrl, cdn), hint, true);
        }
        return new ErrorInfo("PLATFORM_UNREACHABLE",
                "翻译平台不可达: " + probeDetail(platformUrl, platform), hint, true);
    }

    private String probeDetail(String url, ProbeResult result) {
        String detail = result.getStatusCode() != null
                ? "HTTP " + result.getStatusCode()
                : (result.getError() == null ? "unknown" : result.getError());
        return url + " (" + detail + ")";
    }

    private String configHint(String code) {
        if (ConfigException.CONFIG_NOT_FOUND.equals(code)) {
            return "使用 --config <modules.yaml> 或设置 " + ENV_CONFIG;
        }
        if (ConfigException.CONFIG_PARSE_ERROR.equals(code)) {
            return "检查 YAML 语法、缩进与重复键";
        }
        return "按 README 中的 modules.yaml 示例修正配置";
    }
}
