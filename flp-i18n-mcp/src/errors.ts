import type { CliEnvelope, CliResult } from "./cli";

export type ToolErrorCode =
  | "InvalidParams"
  | "UpstreamError"
  | "ConfirmationRequired"
  | "InternalError";

export class ToolError extends Error {
  readonly code: ToolErrorCode;
  readonly hint: string;
  readonly retryable: boolean;
  readonly cliCode: string | null;

  constructor(
    code: ToolErrorCode,
    message: string,
    options: { hint?: string; retryable?: boolean; cliCode?: string | null } = {},
  ) {
    super(message);
    this.name = "ToolError";
    this.code = code;
    this.hint = options.hint ?? "";
    this.retryable = options.retryable ?? false;
    this.cliCode = options.cliCode ?? null;
  }
}

export function toToolError(error: unknown): ToolError {
  if (error instanceof ToolError) {
    return error;
  }
  const message = error instanceof Error ? error.message : String(error);
  return new ToolError("InternalError", message);
}

export function toolErrorFromResult(result: CliResult, envelope: CliEnvelope): ToolError {
  const cliError = envelope.error;
  const message = cliError?.message ?? `CLI 退出码 ${result.exitCode}`;
  const options = {
    hint: cliError?.hint ?? "",
    retryable: cliError?.retryable ?? false,
    cliCode: cliError?.code ?? null,
  };

  switch (result.exitCode) {
    case 2:
      return new ToolError("InvalidParams", message, options);
    case 3:
      return new ToolError("UpstreamError", message, { ...options, retryable: cliError?.retryable ?? true });
    case 4:
      return new ToolError("ConfirmationRequired", message, options);
    default:
      return new ToolError("InternalError", message, options);
  }
}
