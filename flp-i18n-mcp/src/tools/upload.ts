import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { uploadInput } from "../schemas";
import {
  buildWriteArgs,
  errorResult,
  moduleLockKey,
  runCliTool,
  validateWriteRequest,
  type ToolDeps,
} from "./common";

export function registerUploadTool(server: McpServer, deps: ToolDeps): void {
  server.registerTool(
    "i18n_upload",
    {
      title: "上传缺失 Key 到平台",
      description:
        "将本地存在、平台缺失的 key 上传到翻译平台。默认 dryRun=true 只生成计划；写操作必须 dryRun=false + planId，且同一模块串行执行。",
      inputSchema: uploadInput,
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
    },
    async ({ module, os, dryRun, planId, limit, rateMs }) => {
      const invalid = validateWriteRequest(dryRun, planId);
      if (invalid) {
        return errorResult(invalid);
      }

      const args = buildWriteArgs(module, os, dryRun, planId);
      if (limit !== undefined) {
        args.push("--limit", String(limit));
      }
      if (rateMs !== undefined) {
        args.push("--rate-ms", String(rateMs));
      }

      return deps.locks.run(moduleLockKey(module), () =>
        runCliTool(deps, {
          command: "upload",
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
