import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { scanInput } from "../schemas";
import { runCliTool, type ToolDeps } from "./common";

export function registerScanTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_scan",
    {
      title: "扫描本地多语言 Key",
      description: "扫描指定模块的代码与枚举，返回 key 数量、重复项与类加载失败信息（不访问远端）。",
      inputSchema: scanInput,
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ module, os }) => {
      const args = ["--module", module];
      if (os) {
        args.push("--os", os);
      }
      return runCliTool(deps, {
        command: "scan",
        args,
        timeoutMs: deps.config.readTimeoutMs,
        module,
        os,
        record: true,
      });
    },
  );
}
