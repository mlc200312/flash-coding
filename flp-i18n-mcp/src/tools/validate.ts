import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { validateInput } from "../schemas";
import { runCliTool, type ToolDeps } from "./common";

export function registerValidateTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_validate",
    {
      title: "校验本地多语言文件",
      description:
        "扫描本地 key 并检查 properties 文件，返回缺失、空值、语言不一致等 issue 列表（不写入任何文件）。",
      inputSchema: validateInput,
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ module, os }) => {
      const args = ["--module", module];
      if (os) {
        args.push("--os", os);
      }
      return runCliTool(deps, {
        command: "validate",
        args,
        timeoutMs: deps.config.readTimeoutMs,
        module,
        os,
        record: true,
      });
    },
  );
}
