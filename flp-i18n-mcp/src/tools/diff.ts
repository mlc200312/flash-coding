import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { diffInput } from "../schemas";
import { runCliTool, type ToolDeps } from "./common";

export function registerDiffTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_diff",
    {
      title: "对比本地与平台差异",
      description:
        "对比指定模块本地 key 与平台字典，返回各语言缺失、中文冲突与远端不可用明细。需要有效 Token。",
      inputSchema: diffInput,
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ module, os, languages }) => {
      const args = ["--module", module];
      if (os) {
        args.push("--os", os);
      }
      if (languages && languages.length > 0) {
        args.push("--languages", languages.join(","));
      }
      return runCliTool(deps, {
        command: "diff",
        args,
        timeoutMs: deps.config.readTimeoutMs,
        module,
        os,
        record: true,
      });
    },
  );
}
