import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { cacheJarPath, defaultCacheDir, parseMavenCoordinate } from "./cliJar";

export type LogLevel = "error" | "info" | "debug";
export type ConfigField =
  | "cliJar"
  | "cliMavenRepo"
  | "cliMavenCoordinate"
  | "configFile"
  | "allowedRoots"
  | "java"
  | "logLevel";
export type FieldSource = "file" | "env" | "default" | "missing";

export const READ_TIMEOUT_MS = 120_000;
export const WRITE_TIMEOUT_MS = 600_000;

export const ENV_JAVA = "FLP_I18N_JAVA";
export const ENV_CLI_JAR = "FLP_I18N_CLI_JAR";
export const ENV_CONFIG = "FLP_I18N_CONFIG";
export const ENV_ALLOWED_ROOTS = "FLP_I18N_ALLOWED_ROOTS";
export const ENV_TOKEN = "FLP_I18N_TOKEN";
export const ENV_LOG_LEVEL = "FLP_I18N_LOG_LEVEL";
export const ENV_USER_CONFIG = "FLP_I18N_USER_CONFIG";
export const ENV_CLI_MAVEN_REPO = "FLP_I18N_CLI_MAVEN_REPO";
export const ENV_CLI_MAVEN_COORD = "FLP_I18N_CLI_MAVEN_COORD";
export const ENV_CACHE_DIR = "FLP_I18N_CACHE_DIR";

export const DEFAULT_MAVEN_REPO = "https://registry.flashexpress.pub/repository/maven-public/";
export const DEFAULT_MAVEN_COORDINATE = "com.flashfin:flp-i18n-tool:0.1.0";

const USER_CONFIG_DIR_NAME = "flp-i18n-local";
const CONFIG_FILE_NAME = "config.json";

export interface ServerConfig {
  readonly command: string[];
  readonly configPath: string;
  readonly allowedRoots: string[];
  readonly token: string | null;
  readonly logLevel: LogLevel;
  readonly readTimeoutMs: number;
  readonly writeTimeoutMs: number;
  readonly cliJar?: string | null;
  readonly cliJarMode?: "path" | "maven";
  readonly cliMavenRepo?: string | null;
  readonly cliMavenCoordinate?: string | null;
  readonly cacheDir?: string;
}

export interface UserConfigFile {
  version?: number;
  cliJar?: string;
  cliMavenRepo?: string;
  cliMavenCoordinate?: string;
  configFile?: string;
  allowedRoots?: string[];
  java?: string;
  logLevel?: LogLevel;
}

export interface UserConfigPatch {
  cliJar?: string;
  cliMavenRepo?: string;
  cliMavenCoordinate?: string;
  configFile?: string;
  allowedRoots?: string[];
  java?: string;
  logLevel?: LogLevel;
}

export interface ConfigIssue {
  field: ConfigField | "file";
  message: string;
}

export interface ConfigState {
  config: ServerConfig | null;
  error: ConfigError | null;
  sources: Partial<Record<ConfigField, FieldSource>>;
  userConfigPath: string;
  userConfig: UserConfigFile | null;
  userConfigError: string | null;
  issues: ConfigIssue[];
}

export interface ConfigProvider {
  getState(): ConfigState;
  current(): ServerConfig | null;
  reload(): ConfigState;
  readonly userConfigPath: string;
}

export class ConfigError extends Error {
  readonly hint: string;

  constructor(message: string, hint = "") {
    super(message);
    this.name = "ConfigError";
    this.hint = hint;
  }
}

export function resolveUserConfigPath(env: NodeJS.ProcessEnv = process.env): string {
  const explicit = (env[ENV_USER_CONFIG] ?? "").trim();
  if (explicit && !explicit.includes("${")) {
    return path.resolve(explicit);
  }

  const pluginData = (env.QODER_PLUGIN_DATA ?? "").trim();
  if (pluginData && !pluginData.includes("${")) {
    return path.join(pluginData, CONFIG_FILE_NAME);
  }

  const configDir = (env.QODER_CONFIG_DIR ?? "").trim();
  const home = (env.HOME ?? env.USERPROFILE ?? "").trim() || os.homedir();
  const base = configDir && !configDir.includes("${") ? configDir : path.join(home, ".qoder");
  return path.join(base, "plugins", "data", USER_CONFIG_DIR_NAME, CONFIG_FILE_NAME);
}

export function loadConfig(
  env: NodeJS.ProcessEnv = process.env,
  userConfigPath: string = resolveUserConfigPath(env),
): ServerConfig {
  const state = resolveConfig(env, userConfigPath);
  if (!state.config) {
    throw state.error ?? new ConfigError("配置无效");
  }
  return state.config;
}

export function resolveConfig(env: NodeJS.ProcessEnv, userConfigPath: string): ConfigState {
  const issues: ConfigIssue[] = [];
  const sources: Partial<Record<ConfigField, FieldSource>> = {};
  const { value: file, error: fileError } = readUserConfigFile(userConfigPath);
  if (fileError) {
    issues.push({ field: "file", message: fileError });
  }

  const cacheDir = defaultCacheDir(env);
  const fileJarDisabled = typeof file?.cliJar === "string" && file.cliJar.trim() === "";
  const fileJar = fileJarDisabled ? null : stringValue(file?.cliJar, "cliJar", issues);
  const envJar = envString(env, ENV_CLI_JAR);
  const explicitJar = fileJarDisabled ? null : fileJar ?? envJar;

  let cliJarMode: "path" | "maven" = "path";
  let cliJarPath: string | null = null;
  let mavenRepo: string | null = null;
  let mavenCoordinate: string | null = null;

  if (explicitJar) {
    cliJarPath = explicitJar;
    sources.cliJar = fileJar ? "file" : "env";
    checkFile(explicitJar, "cliJar", "CLI jar", issues);
  } else {
    const fileRepo = stringValue(file?.cliMavenRepo, "cliMavenRepo", issues);
    const envRepo = envString(env, ENV_CLI_MAVEN_REPO);
    mavenRepo = mavenRepoValue(fileRepo ?? envRepo ?? DEFAULT_MAVEN_REPO, issues);
    sources.cliMavenRepo = fileRepo ? "file" : envRepo ? "env" : "default";

    const fileCoord = stringValue(file?.cliMavenCoordinate, "cliMavenCoordinate", issues);
    const envCoord = envString(env, ENV_CLI_MAVEN_COORD);
    const coordinate = fileCoord ?? envCoord ?? DEFAULT_MAVEN_COORDINATE;
    mavenCoordinate = mavenCoordinateValue(coordinate, issues);
    sources.cliMavenCoordinate = fileCoord ? "file" : envCoord ? "env" : "default";

    if (mavenRepo && mavenCoordinate) {
      const parsed = parseMavenCoordinate(mavenCoordinate);
      if (parsed) {
        cliJarPath = cacheJarPath(cacheDir, parsed);
        cliJarMode = "maven";
      }
    }
    sources.cliJar = fileRepo || fileCoord ? "file" : envRepo || envCoord ? "env" : "default";
  }

  const fileConfig = stringValue(file?.configFile, "configFile", issues);
  const configFile = fileConfig ?? envString(env, ENV_CONFIG);
  sources.configFile = fileConfig ? "file" : configFile ? "env" : "missing";
  if (!configFile) {
    issues.push({
      field: "configFile",
      message: `缺少 modules.yaml 路径（${ENV_CONFIG} 或配置文件 configFile）`,
    });
  } else {
    checkFile(configFile, "configFile", "modules.yaml", issues);
  }

  const fileRoots = arrayValue(file?.allowedRoots, "allowedRoots", issues);
  const envRootsRaw = envString(env, ENV_ALLOWED_ROOTS);
  const allowedRoots = fileRoots ?? splitRoots(envRootsRaw);
  sources.allowedRoots = fileRoots ? "file" : envRootsRaw ? "env" : "missing";
  if (allowedRoots.length === 0) {
    issues.push({
      field: "allowedRoots",
      message: `缺少允许访问的根目录（${ENV_ALLOWED_ROOTS} 或配置文件 allowedRoots）`,
    });
  }

  const fileJava = stringValue(file?.java, "java", issues);
  const java = fileJava ?? envString(env, ENV_JAVA);
  sources.java = fileJava ? "file" : java ? "env" : "default";

  const fileLogLevel = logLevelValue(file?.logLevel, "logLevel", issues);
  const envLogLevel = parseLogLevelSoft(envString(env, ENV_LOG_LEVEL), issues);
  const logLevel = fileLogLevel ?? envLogLevel ?? "info";
  sources.logLevel = fileLogLevel ? "file" : envLogLevel ? "env" : "default";

  const token = envString(env, ENV_TOKEN);

  if (issues.length > 0) {
    return {
      config: null,
      error: new ConfigError(issues[0].message, buildHint(issues)),
      sources,
      userConfigPath,
      userConfig: file,
      userConfigError: fileError,
      issues,
    };
  }

  return {
    config: {
      command: [java ?? "java", "-jar", cliJarPath as string],
      configPath: configFile as string,
      allowedRoots,
      token,
      logLevel,
      readTimeoutMs: READ_TIMEOUT_MS,
      writeTimeoutMs: WRITE_TIMEOUT_MS,
      cliJar: cliJarPath,
      cliJarMode,
      cliMavenRepo: mavenRepo,
      cliMavenCoordinate: mavenCoordinate,
      cacheDir,
    },
    error: null,
    sources,
    userConfigPath,
    userConfig: file,
    userConfigError: null,
    issues,
  };
}

export function validateUserConfigPatch(patch: UserConfigPatch): {
  patch: UserConfigPatch;
  issues: ConfigIssue[];
  checks: Record<string, string>;
} {
  const issues: ConfigIssue[] = [];
  const checks: Record<string, string> = {};
  const normalized: UserConfigPatch = {};

  if (patch.cliJar !== undefined) {
    if (patch.cliJar.trim() === "") {
      normalized.cliJar = "";
      checks.cliJar = "将忽略本地 jar 与环境变量，改用 Maven 自动下载";
    } else {
      const value = cleanPath(patch.cliJar, "cliJar", issues);
      if (value) {
        normalized.cliJar = value;
        const status = checkFile(value, "cliJar", "CLI jar", issues);
        if (status) {
          checks.cliJar = status;
        }
      }
    }
  }

  if (patch.cliMavenRepo !== undefined) {
    const value = mavenRepoValue(patch.cliMavenRepo, issues);
    if (value) {
      normalized.cliMavenRepo = value;
      checks.cliMavenRepo = "仓库地址格式有效";
    }
  }

  if (patch.cliMavenCoordinate !== undefined) {
    const value = mavenCoordinateValue(patch.cliMavenCoordinate, issues);
    if (value) {
      normalized.cliMavenCoordinate = value;
      checks.cliMavenCoordinate = "坐标格式有效";
    }
  }

  if (patch.configFile !== undefined) {
    const value = cleanPath(patch.configFile, "configFile", issues);
    if (value) {
      normalized.configFile = value;
      const status = checkFile(value, "configFile", "modules.yaml", issues);
      if (status) {
        checks.configFile = status;
      }
    }
  }

  if (patch.allowedRoots !== undefined) {
    const roots: string[] = [];
    const seen = new Set<string>();
    for (const raw of patch.allowedRoots) {
      const value = cleanPath(raw, "allowedRoots", issues);
      if (!value) {
        continue;
      }
      if (value === path.parse(value).root) {
        issues.push({ field: "allowedRoots", message: `允许根目录不能是文件系统根目录: ${value}` });
        continue;
      }
      if (seen.has(value)) {
        continue;
      }
      seen.add(value);
      let stat: fs.Stats;
      try {
        stat = fs.statSync(value);
      } catch {
        issues.push({ field: "allowedRoots", message: `允许根目录不存在或不可访问: ${value}` });
        continue;
      }
      if (!stat.isDirectory()) {
        issues.push({ field: "allowedRoots", message: `允许根目录必须是目录: ${value}` });
        continue;
      }
      roots.push(value);
    }
    if (roots.length === 0) {
      issues.push({ field: "allowedRoots", message: "allowedRoots 至少要包含一个已存在的目录" });
    } else {
      normalized.allowedRoots = roots;
      checks.allowedRoots = `已确认 ${roots.length} 个目录`;
    }
  }

  if (patch.java !== undefined) {
    const value = stripControl(patch.java, "java", issues);
    if (value) {
      normalized.java = value;
      if (value.includes("/") || value.includes("\\")) {
        const status = checkFile(value, "java", "Java 可执行文件", issues);
        if (status) {
          checks.java = status;
        }
      } else {
        checks.java = "使用 PATH 中的命令";
      }
    }
  }

  if (patch.logLevel !== undefined) {
    if (patch.logLevel === "error" || patch.logLevel === "info" || patch.logLevel === "debug") {
      normalized.logLevel = patch.logLevel;
    } else {
      issues.push({ field: "logLevel", message: `日志级别非法: ${String(patch.logLevel)}（可选 error / info / debug）` });
    }
  }

  return { patch: normalized, issues, checks };
}

export function writeUserConfig(
  userConfigPath: string,
  patch: UserConfigPatch,
): { backupPath: string | null; written: UserConfigFile } {
  fs.mkdirSync(path.dirname(userConfigPath), { recursive: true });

  let raw: string | null = null;
  try {
    raw = fs.readFileSync(userConfigPath, "utf8");
  } catch (error) {
    if (!isNotFound(error)) {
      throw new ConfigError(`无法读取用户配置文件: ${userConfigPath}`, describeError(error));
    }
  }

  let existing: Record<string, unknown> = {};
  if (raw !== null && raw.trim()) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        existing = parsed as Record<string, unknown>;
      }
    } catch {
      existing = {};
    }
  }

  const next: Record<string, unknown> = { version: 1, ...existing };
  for (const [key, value] of Object.entries(patch)) {
    if (value !== undefined) {
      next[key] = value;
    }
  }

  let backupPath: string | null = null;
  if (raw !== null) {
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    backupPath = `${userConfigPath}.bak-${stamp}`;
    fs.copyFileSync(userConfigPath, backupPath);
  }

  const tmpPath = `${userConfigPath}.tmp-${process.pid}-${Date.now()}`;
  fs.writeFileSync(tmpPath, `${JSON.stringify(next, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(tmpPath, userConfigPath);

  return { backupPath, written: next as UserConfigFile };
}

export class ConfigStore implements ConfigProvider {
  private state: ConfigState;
  private stamp: string;
  readonly userConfigPath: string;

  constructor(
    private readonly env: NodeJS.ProcessEnv = process.env,
    userConfigPath: string = resolveUserConfigPath(env),
  ) {
    this.userConfigPath = userConfigPath;
    this.stamp = fileStamp(userConfigPath);
    this.state = resolveConfig(env, userConfigPath);
  }

  getState(): ConfigState {
    const stamp = fileStamp(this.userConfigPath);
    if (stamp !== this.stamp) {
      this.stamp = stamp;
      this.state = resolveConfig(this.env, this.userConfigPath);
    }
    return this.state;
  }

  current(): ServerConfig | null {
    return this.getState().config;
  }

  reload(): ConfigState {
    this.stamp = fileStamp(this.userConfigPath);
    this.state = resolveConfig(this.env, this.userConfigPath);
    return this.state;
  }
}

export class StaticConfigStore implements ConfigProvider {
  readonly userConfigPath = "";

  constructor(private readonly config: ServerConfig) {}

  getState(): ConfigState {
    return {
      config: this.config,
      error: null,
      sources: {},
      userConfigPath: "",
      userConfig: null,
      userConfigError: null,
      issues: [],
    };
  }

  current(): ServerConfig {
    return this.config;
  }

  reload(): ConfigState {
    return this.getState();
  }
}

function readUserConfigFile(userConfigPath: string): { value: UserConfigFile | null; error: string | null } {
  let text: string;
  try {
    text = fs.readFileSync(userConfigPath, "utf8");
  } catch (error) {
    if (isNotFound(error)) {
      return { value: null, error: null };
    }
    return { value: null, error: `无法读取用户配置文件 ${userConfigPath}: ${describeError(error)}` };
  }

  if (!text.trim()) {
    return { value: null, error: null };
  }

  try {
    const parsed: unknown = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { value: null, error: `用户配置文件必须是 JSON 对象: ${userConfigPath}` };
    }
    return { value: parsed as UserConfigFile, error: null };
  } catch (error) {
    return { value: null, error: `用户配置文件 JSON 解析失败: ${describeError(error)}` };
  }
}

function stringValue(value: unknown, field: ConfigField, issues: ConfigIssue[]): string | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== "string") {
    issues.push({ field, message: `${field} 必须是字符串` });
    return null;
  }
  return stripControl(value, field, issues);
}

function arrayValue(value: unknown, field: ConfigField, issues: ConfigIssue[]): string[] | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value === "string") {
    return value.trim() ? [value.trim()] : [];
  }
  if (!Array.isArray(value)) {
    issues.push({ field, message: `${field} 必须是字符串数组` });
    return null;
  }
  const roots: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") {
      issues.push({ field, message: `${field} 必须是字符串数组` });
      return null;
    }
    const trimmed = item.trim();
    if (trimmed) {
      roots.push(trimmed);
    }
  }
  return roots;
}

function logLevelValue(value: unknown, field: ConfigField, issues: ConfigIssue[]): LogLevel | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (value === "error" || value === "info" || value === "debug") {
    return value;
  }
  issues.push({ field, message: `${field} 取值非法: ${String(value)}（可选 error / info / debug）` });
  return null;
}

function parseLogLevelSoft(raw: string | null, issues: ConfigIssue[]): LogLevel | null {
  if (!raw) {
    return null;
  }
  const value = raw.toLowerCase();
  if (value === "error" || value === "info" || value === "debug") {
    return value;
  }
  issues.push({ field: "logLevel", message: `${ENV_LOG_LEVEL} 取值非法: ${raw}（可选 error / info / debug）` });
  return null;
}

function envString(env: NodeJS.ProcessEnv, name: string): string | null {
  const value = (env[name] ?? "").trim();
  if (!value || value.includes("${")) {
    return null;
  }
  return value;
}

function splitRoots(raw: string | null): string[] {
  if (!raw) {
    return [];
  }
  return raw
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

function mavenRepoValue(raw: string, issues: ConfigIssue[]): string | null {
  const value = stripControl(raw, "cliMavenRepo", issues);
  if (!value) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    issues.push({ field: "cliMavenRepo", message: `Maven 仓库地址非法: ${value}` });
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    issues.push({ field: "cliMavenRepo", message: `Maven 仓库地址必须是 http/https: ${value}` });
    return null;
  }
  if (url.username || url.password) {
    issues.push({
      field: "cliMavenRepo",
      message: "Maven 仓库地址不能包含账号密码；凭据请配置在 Maven settings.xml 或网络层",
    });
    return null;
  }
  return value.replace(/\/+$/, "");
}

function mavenCoordinateValue(raw: string, issues: ConfigIssue[]): string | null {
  const value = stripControl(raw, "cliMavenCoordinate", issues);
  if (!value) {
    return null;
  }
  if (!parseMavenCoordinate(value)) {
    issues.push({
      field: "cliMavenCoordinate",
      message: `Maven 坐标非法: ${value}（格式 groupId:artifactId:version[:classifier]，不支持 SNAPSHOT）`,
    });
    return null;
  }
  return value;
}

function cleanPath(raw: string, field: ConfigField, issues: ConfigIssue[]): string | null {
  const value = stripControl(raw, field, issues);
  if (!value) {
    return null;
  }
  if (!path.isAbsolute(value)) {
    issues.push({ field, message: `${field} 必须是绝对路径: ${value}` });
    return null;
  }
  return path.normalize(value);
}

function stripControl(raw: string, field: ConfigField, issues: ConfigIssue[]): string | null {
  const value = raw.trim();
  if (!value) {
    issues.push({ field, message: `${field} 不能为空` });
    return null;
  }
  if (value.includes("\0") || /[\r\n]/.test(value)) {
    issues.push({ field, message: `${field} 不能包含换行或空字符` });
    return null;
  }
  if (/^eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value) || /^Bearer\s+/i.test(value)) {
    issues.push({ field, message: `${field} 疑似 Token，Token 只能通过环境变量 ${ENV_TOKEN} 提供` });
    return null;
  }
  return value;
}

function checkFile(filePath: string, field: ConfigField, label: string, issues: ConfigIssue[]): string | null {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(filePath);
  } catch (error) {
    issues.push({ field, message: `${label} 路径不可访问: ${filePath}（${describeError(error)}）` });
    return null;
  }
  if (!stat.isFile()) {
    issues.push({ field, message: `${label} 必须指向一个文件: ${filePath}` });
    return null;
  }
  return "文件存在";
}

function buildHint(issues: ConfigIssue[]): string {
  const details = issues.map((issue) => issue.message).join("；");
  return `${details}。可调用 i18n_config_get 查看当前配置，再用 i18n_config_set 修改，或在 Qoder 中执行 /i18n-config。`;
}

function fileStamp(filePath: string): string {
  try {
    const stat = fs.statSync(filePath);
    return `${stat.mtimeMs}:${stat.size}`;
  } catch {
    return "missing";
  }
}

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as NodeJS.ErrnoException).code === "ENOENT";
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
