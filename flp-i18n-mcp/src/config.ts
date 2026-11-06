import * as fs from "node:fs";
import * as path from "node:path";

export type LogLevel = "error" | "info" | "debug";

export const READ_TIMEOUT_MS = 120_000;
export const WRITE_TIMEOUT_MS = 600_000;

export interface ServerConfig {
  readonly command: string[];
  readonly configPath: string;
  readonly allowedRoots: string[];
  readonly token: string | null;
  readonly logLevel: LogLevel;
  readonly readTimeoutMs: number;
  readonly writeTimeoutMs: number;
}

export class ConfigError extends Error {
  readonly hint: string;

  constructor(message: string, hint = "") {
    super(message);
    this.name = "ConfigError";
    this.hint = hint;
  }
}

const ENV_JAVA = "FLP_I18N_JAVA";
const ENV_CLI_JAR = "FLP_I18N_CLI_JAR";
const ENV_CONFIG = "FLP_I18N_CONFIG";
const ENV_ALLOWED_ROOTS = "FLP_I18N_ALLOWED_ROOTS";
const ENV_TOKEN = "FLP_I18N_TOKEN";
const ENV_LOG_LEVEL = "FLP_I18N_LOG_LEVEL";

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const jarPath = requiredValue(env, ENV_CLI_JAR, "Java CLI jar 路径");
  const configPath = requiredValue(env, ENV_CONFIG, "modules.yaml 路径");
  const allowedRootsRaw = requiredValue(env, ENV_ALLOWED_ROOTS, "允许访问的工作区根目录");

  requireFile(jarPath, ENV_CLI_JAR);
  requireFile(configPath, ENV_CONFIG);

  const allowedRoots = allowedRootsRaw
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  if (allowedRoots.length === 0) {
    throw new ConfigError(`${ENV_ALLOWED_ROOTS} 为空`, "使用路径分隔符连接多个允许的根目录");
  }

  const javaCommand = (env[ENV_JAVA] ?? "").trim() || "java";
  const token = (env[ENV_TOKEN] ?? "").trim() || null;

  return {
    command: [javaCommand, "-jar", jarPath],
    configPath,
    allowedRoots,
    token,
    logLevel: parseLogLevel(env[ENV_LOG_LEVEL]),
    readTimeoutMs: READ_TIMEOUT_MS,
    writeTimeoutMs: WRITE_TIMEOUT_MS,
  };
}

function requiredValue(env: NodeJS.ProcessEnv, name: string, label: string): string {
  const value = (env[name] ?? "").trim();
  if (!value) {
    throw new ConfigError(`缺少必需环境变量 ${name}（${label}）`, `设置 ${name} 后重启 MCP Server`);
  }
  return value;
}

function requireFile(filePath: string, envName: string): void {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(filePath);
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new ConfigError(`${envName} 指向的路径不可访问: ${filePath}`, reason);
  }
  if (!stat.isFile()) {
    throw new ConfigError(`${envName} 必须指向一个文件: ${filePath}`);
  }
}

function parseLogLevel(raw: string | undefined): LogLevel {
  const value = (raw ?? "").trim().toLowerCase();
  if (!value) {
    return "info";
  }
  if (value === "error" || value === "info" || value === "debug") {
    return value;
  }
  throw new ConfigError(
    `${ENV_LOG_LEVEL} 取值非法: ${value}`,
    "可选值: error / info / debug（默认 info）",
  );
}
