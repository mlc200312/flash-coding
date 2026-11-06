import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { downloadInput } from "../schemas";
import {
  buildWriteArgs,
  errorResult,
  moduleLockKey,
  runCliTool,
  validateWriteRequest,
  type ToolDeps,
} from "./common";

export function registerDownloadTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_download",
    {
      title: "下载平台译文到本地",
      description:
        "生成将平台译文写回本地 properties 的计划。默认 dryRun=true；写操作必须 dryRun=false + planId，会先做 git 检查并备份，同一模块串行执行。",
      inputSchema: downloadInput,
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
    },
    async ({ module, os, languages, dryRun, planId }) => {
      const invalid = validateWriteRequest(dryRun, planId);
      if (invalid) {
        return errorResult(invalid);
      }

      const args = buildWriteArgs(module, os, dryRun, planId);
      if (languages && languages.length > 0) {
        args.push("--languages", languages.join(","));
      }

      return deps.locks.run(moduleLockKey(module), () =>
        runCliTool(deps, {
          command: "download",
          args,
          timeoutMs: deps.config.writeTimeoutMs,
          module,
          os,
          record: true,
        }),
      );
    },
  );
}
