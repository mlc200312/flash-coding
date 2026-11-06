import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import type { CliEnvelope, CliRunner } from "../cli";
import type { ServerConfig } from "../config";
import { ToolError, toolErrorFromResult, toToolError } from "../errors";
import type { Logger, Redactor } from "../logger";
import type { ModuleLocks } from "../lock";
import type { ReportStore } from "../report";

export interface ToolDeps {
  config: ServerConfig;
  runner: CliRunner;
  logger: Logger;
  reports: ReportStore;
  locks: ModuleLocks;
  redact: Redactor;
}

export function textResult(payload: unknown, isError = false): CallToolResult {
  const result: CallToolResult = {
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
  };
  if (isError) {
    result.isError = true;
  }
  return result;
}

export function errorResult(error: ToolError, envelope: CliEnvelope | null = null): CallToolResult {
  return textResult(
    {
      ok: false,
      command: envelope?.command ?? null,
      data: envelope?.data ?? null,
      warnings: envelope?.warnings ?? [],
      error: {
        code: error.code,
        cliCode: error.cliCode,
        message: error.message,
        hint: error.hint,
        retryable: error.retryable,
      },
    },
    true,
  );
}

export interface RunOptions {
  command: string;
  args: string[];
  timeoutMs: number;
  module?: string;
  os?: string;
  record?: boolean;
  acceptTokenDiagnostic?: boolean;
}

export async function runCliTool(deps: ToolDeps, options: RunOptions): Promise<CallToolResult> {
  try {
    const result = await deps.runner.run([options.command, ...options.args], options.timeoutMs);
    const envelope = result.envelope;

    if (options.record) {
      deps.reports.record({
        generatedAt: new Date().toISOString(),
        tool: options.command,
        module: options.module ?? null,
        os: options.os ?? null,
        result: envelope,
      });
    }

    const tokenDiagnostic =
      options.acceptTokenDiagnostic === true &&
      envelope.error !== null &&
      (envelope.error.code === "TOKEN_MISSING" || envelope.error.code === "TOKEN_EXPIRED");

    if (envelope.ok || tokenDiagnostic) {
      if (tokenDiagnostic) {
        deps.logger.info(`status 诊断: ${envelope.error?.code}`);
      }
      return textResult(envelope);
    }

    return errorResult(toolErrorFromResult(result, envelope), envelope);
  } catch (error) {
    const toolError = toToolError(error);
    deps.logger.debug(`工具 ${options.command} 失败: ${toolError.message}`);
    return errorResult(toolError);
  }
}

export function validateWriteRequest(dryRun: boolean, planId: string | undefined): ToolError | null {
  if (dryRun && planId) {
    return new ToolError("InvalidParams", "dryRun=true 时不应传 planId", {
      hint: "先不传 planId 执行 dryRun；确认后以 dryRun=false + 同一 planId 执行 apply",
      cliCode: "PLAN_ID_UNEXPECTED",
    });
  }
  if (!dryRun && !planId) {
    return new ToolError(
      "ConfirmationRequired",
      "写操作需要用户确认：dryRun=false 时必须提供 dryRun 返回的 planId",
      {
        hint:
          "先以 dryRun=true 调用获取 planId，向用户展示计划摘要并取得明确同意后，再以 dryRun=false + 同一 planId 调用；不得跳过确认或复用其他模块的 planId",
        cliCode: "PLAN_ID_REQUIRED",
      },
    );
  }
  return null;
}

export function buildWriteArgs(
  module: string,
  os: string | undefined,
  dryRun: boolean,
  planId: string | undefined,
): string[] {
  const args = ["--module", module];
  if (os) {
    args.push("--os", os);
  }
  if (!dryRun && planId) {
    args.push("--apply", "--plan-id", planId);
  }
  return args;
}

export function moduleLockKey(module: string): string {
  return module;
}
