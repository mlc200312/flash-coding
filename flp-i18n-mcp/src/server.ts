import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { CliRunner } from "./cli";
import {
  READ_TIMEOUT_MS,
  StaticConfigStore,
  WRITE_TIMEOUT_MS,
  type ConfigProvider,
  type ServerConfig,
} from "./config";
import { Logger, createRedactor, type Redactor } from "./logger";
import { ModuleLocks } from "./lock";
import { registerPrompts } from "./prompt";
import { ReportStore } from "./report";
import { registerResources } from "./resources";
import { registerTools } from "./tools";
import type { ToolDeps } from "./tools/common";

export const SERVER_NAME = "flp-i18n-mcp";
export const SERVER_VERSION = "0.1.4";

export interface ServerOverrides {
  logger?: Logger;
  redact?: Redactor;
  runner?: CliRunner;
  reports?: ReportStore;
  locks?: ModuleLocks;
}

export interface ServerBundle {
  server: McpServer;
  deps: ToolDeps;
}

export function createServer(
  config: ServerConfig | ConfigProvider,
  overrides: ServerOverrides = {},
): ServerBundle {
  const provider = isConfigProvider(config) ? config : new StaticConfigStore(config);
  const initialState = provider.getState();
  const redact =
    overrides.redact ??
    createRedactor([initialState.config?.token ?? null, process.env.FLP_I18N_TOKEN ?? null]);
  const logger = overrides.logger ?? new Logger(initialState.config?.logLevel ?? "info", redact);
  const fallbackConfig: ServerConfig = {
    command: [],
    configPath: "",
    allowedRoots: [],
    token: null,
    logLevel: "info",
    readTimeoutMs: READ_TIMEOUT_MS,
    writeTimeoutMs: WRITE_TIMEOUT_MS,
    cliJar: null,
    cliJarMode: "path",
    cliMavenRepo: null,
    cliMavenCoordinate: null,
    cacheDir: "",
  };
  const deps: ToolDeps = {
    store: provider,
    get config(): ServerConfig {
      return provider.getState().config ?? fallbackConfig;
    },
    runner: overrides.runner ?? new CliRunner(provider, logger),
    logger,
    reports: overrides.reports ?? new ReportStore(),
    locks: overrides.locks ?? new ModuleLocks(),
    redact,
  };

  const server = new McpServer(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      instructions:
        "FLP 多语言同步工具。配置工具：i18n_config_get / i18n_config_set（jar、modules.yaml、允许根目录）。读工具：i18n_status / i18n_scan / i18n_diff / i18n_validate。写工具：i18n_upload / i18n_download，默认 dryRun=true，写入必须携带 dryRun 返回的 planId 并在用户确认后以 dryRun=false 执行。",
    },
  );

  registerTools(server, deps);
  registerResources(server, deps);
  registerPrompts(server);

  return { server, deps };
}

function isConfigProvider(value: ServerConfig | ConfigProvider): value is ConfigProvider {
  return typeof (value as Partial<ConfigProvider>).getState === "function";
}
