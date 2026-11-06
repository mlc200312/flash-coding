import * as fs from "node:fs/promises";

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import type { ToolDeps } from "./tools/common";

export function registerResources(server: McpServer, deps: ToolDeps): void {
  server.registerResource(
    "i18n-modules",
    "i18n://modules",
    {
      title: "modules.yaml",
      description: "当前 MCP Server 使用的模块配置（Token 等敏感值已脱敏）",
      mimeType: "text/yaml",
    },
    async (uri) => {
      let text: string;
      try {
        text = await fs.readFile(deps.config.configPath, "utf8");
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`无法读取配置文件 ${deps.config.configPath}: ${message}`);
      }
      return {
        contents: [{ uri: uri.href, mimeType: "text/yaml", text: deps.redact(text) }],
      };
    },
  );

  server.registerResource(
    "i18n-reports-latest",
    "i18n://reports/latest",
    {
      title: "最新同步报告",
      description: "本次 Server 会话最近一次 scan/diff/validate/upload/download 的完整 JSON 结果",
      mimeType: "application/json",
    },
    async (uri) => {
      const latest = deps.reports.latest();
      const payload = latest ?? {
        empty: true,
        message: "本次 Server 会话尚未产生报告；先调用 i18n_scan / i18n_diff / i18n_validate / i18n_upload / i18n_download",
      };
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "application/json",
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    },
  );
}
