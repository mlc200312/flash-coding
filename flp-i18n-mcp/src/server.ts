import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { CliRunner } from "./cli";
import type { ServerConfig } from "./config";
import { Logger, createRedactor, type Redactor } from "./logger";
import { ModuleLocks } from "./lock";
import { registerPrompts } from "./prompt";
import { ReportStore } from "./report";
import { registerResources } from "./resources";
import { registerTools } from "./tools";
import type { ToolDeps } from "./tools/common";

export const SERVER_NAME = "flp-i18n-mcp";
export const SERVER_VERSION = "0.1.0";

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

export function createServer(config: ServerConfig, overrides: ServerOverrides = {}): ServerBundle {
  const redact = overrides.redact ?? createRedactor([config.token]);
  const logger = overrides.logger ?? new Logger(config.logLevel, redact);
  const deps: ToolDeps = {
    config,
    runner: overrides.runner ?? new CliRunner(config, logger),
    logger,
    reports: overrides.reports ?? new ReportStore(),
    locks: overrides.locks ?? new ModuleLocks(),
    redact,
  };

  const server = new McpServer(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      instructions:
        "FLP 多语言同步工具。读工具：i18n_status / i18n_scan / i18n_diff / i18n_validate。写工具：i18n_upload / i18n_download，默认 dryRun=true，写入必须携带 dryRun 返回的 planId 并在用户确认后以 dryRun=false 执行。",
    },
  );

  registerTools(server, deps);
  registerResources(server, deps);
  registerPrompts(server);

  return { server, deps };
}
