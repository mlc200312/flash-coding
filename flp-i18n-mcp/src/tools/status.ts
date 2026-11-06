import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { statusInput } from "../schemas";
import { runCliTool, type ToolDeps } from "./common";

export function registerStatusTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_status",
    {
      title: "I18n 状态检查",
      description:
        "检查 modules.yaml 配置、Token 有效期与远端字典平台连通性。无参数；Token 缺失时仍返回诊断信息。",
      inputSchema: statusInput,
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async () =>
      runCliTool(deps, {
        command: "status",
        args: [],
        timeoutMs: deps.config.readTimeoutMs,
        acceptTokenDiagnostic: true,
      }),
  );
}
