import * as fs from "node:fs";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import {
  validateUserConfigPatch,
  writeUserConfig,
  type ConfigState,
  type UserConfigPatch,
} from "../config";
import { ToolError } from "../errors";
import { configGetInput, configSetInput } from "../schemas";
import { errorResult, textResult, type ToolDeps } from "./common";

export function registerConfigTools(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_config_get",
    {
      title: "查看 I18n 配置",
      description:
        "查看当前生效的 jar、Maven 仓库/坐标、modules.yaml、允许根目录、Java 与日志级别，并标注每个值的来源（用户配置文件 / 环境变量 / 默认值）与校验结果。jar 未显式配置时按 Maven 坐标从仓库下载并缓存。不打印 Token。",
      inputSchema: configGetInput,
      annotations: { readOnlyHint: true },
    },
    async () => textResult(describeConfig(deps.store.getState())),
  );

  server.registerTool(
    "i18n_config_set",
    {
      title: "修改 I18n 配置",
      description:
        "校验并写入用户配置文件（默认 dryRun=true 只预览）。可修改 cliJar / cliMavenRepo / cliMavenCoordinate / configFile / allowedRoots / java / logLevel；留空 cliJar 时按 Maven 坐标自动下载。保存后立即生效，无需重启 MCP Server。不会写入 Token，也不会修改业务仓库。",
      inputSchema: configSetInput,
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
    },
    async (input) => {
      if (!deps.store.userConfigPath) {
        return errorResult(
          new ToolError("InternalError", "当前 MCP Server 未启用用户配置文件", {
            hint: "该 Server 以静态测试配置启动，无法写入用户配置",
            cliCode: "CONFIG_READONLY",
          }),
        );
      }

      const patch: UserConfigPatch = {};
      if (input.cliJar !== undefined) {
        patch.cliJar = input.cliJar;
      }
      if (input.cliMavenRepo !== undefined) {
        patch.cliMavenRepo = input.cliMavenRepo;
      }
      if (input.cliMavenCoordinate !== undefined) {
        patch.cliMavenCoordinate = input.cliMavenCoordinate;
      }
      if (input.configFile !== undefined) {
        patch.configFile = input.configFile;
      }
      if (input.allowedRoots !== undefined) {
        patch.allowedRoots = input.allowedRoots;
      }
      if (input.java !== undefined) {
        patch.java = input.java;
      }
      if (input.logLevel !== undefined) {
        patch.logLevel = input.logLevel;
      }

      if (Object.keys(patch).length === 0) {
        return errorResult(
          new ToolError("InvalidParams", "至少提供一个配置项", {
            hint: "可传 cliJar / cliMavenRepo / cliMavenCoordinate / configFile / allowedRoots / java / logLevel",
            cliCode: "CONFIG_EMPTY_PATCH",
          }),
        );
      }

      const { patch: normalized, issues, checks } = validateUserConfigPatch(patch);
      if (issues.length > 0) {
        return errorResult(
          new ToolError("InvalidParams", `配置校验失败: ${issues[0].message}`, {
            hint: issues.map((issue) => issue.message).join("；"),
            cliCode: "CONFIG_INVALID",
          }),
        );
      }

      const dryRun = input.dryRun !== false;
      if (dryRun) {
        return textResult({
          ok: true,
          dryRun: true,
          userConfigPath: deps.store.userConfigPath,
          patch: normalized,
          checks,
          effective: summarizeConfig(deps.store.getState()),
        });
      }

      const { backupPath } = writeUserConfig(deps.store.userConfigPath, normalized);
      const state = deps.store.reload();

      return textResult({
        ok: true,
        dryRun: false,
        userConfigPath: deps.store.userConfigPath,
        patch: normalized,
        checks,
        backupPath,
        effective: summarizeConfig(state),
      });
    },
  );
}

function describeConfig(state: ConfigState): Record<string, unknown> {
  return {
    ok: state.config !== null,
    userConfigPath: state.userConfigPath,
    userConfigExists: exists(state.userConfigPath),
    userConfigError: state.userConfigError,
    effective: summarizeConfig(state),
    error: state.error
      ? { message: state.error.message, hint: state.error.hint }
      : null,
  };
}

function summarizeConfig(state: ConfigState): Record<string, unknown> {
  const config = state.config;
  return {
    ready: config !== null,
    values: config
      ? {
          cliJar: config.cliJar ?? config.command[2] ?? null,
          cliJarMode: config.cliJarMode ?? "path",
          cliJarCached: config.cliJar ? exists(config.cliJar) : false,
          cliMavenRepo: config.cliMavenRepo ?? null,
          cliMavenCoordinate: config.cliMavenCoordinate ?? null,
          cacheDir: config.cacheDir ?? null,
          configFile: config.configPath,
          allowedRoots: config.allowedRoots,
          java: config.command[0],
          logLevel: config.logLevel,
          token: { present: config.token !== null },
        }
      : null,
    sources: state.sources,
    issues: state.issues,
  };
}

function exists(filePath: string): boolean {
  try {
    return fs.statSync(filePath).isFile();
  } catch {
    return false;
  }
}
