import { spawn } from "node:child_process";

import type { ServerConfig } from "./config";
import { ToolError } from "./errors";
import type { Logger } from "./logger";

const STDOUT_LIMIT_BYTES = 64 * 1024 * 1024;
const STDERR_LIMIT_BYTES = 512 * 1024;

export interface CliErrorInfo {
  code: string;
  message: string;
  hint: string;
  retryable: boolean;
}

export interface CliEnvelope {
  ok: boolean;
  command: string;
  data: unknown;
  warnings: string[];
  error: CliErrorInfo | null;
}

export interface CliResult {
  exitCode: number;
  envelope: CliEnvelope;
  warnings: string[];
  stderr: string;
  durationMs: number;
}

export class CliRunner {
  constructor(
    private readonly config: ServerConfig,
    private readonly logger: Logger,
  ) {}

  async run(args: string[], timeoutMs: number): Promise<CliResult> {
    const [bin, ...prefixArgs] = this.config.command;
    const argv = [...prefixArgs, ...args, "--json"];
    const startedAt = Date.now();
    this.logger.debug(`执行 CLI: ${bin} ${argv.join(" ")}`);

    return new Promise<CliResult>((resolve, reject) => {
      const child = spawn(bin, argv, {
        cwd: process.cwd(),
        env: process.env,
        stdio: ["ignore", "pipe", "pipe"],
      });

      let stdout = "";
      let stderr = "";
      let stdoutBytes = 0;
      let stderrBytes = 0;
      let timedOut = false;
      let settled = false;
      let timer: NodeJS.Timeout | undefined;

      const finish = (action: () => void): void => {
        if (settled) {
          return;
        }
        settled = true;
        if (timer) {
          clearTimeout(timer);
        }
        action();
      };

      timer = setTimeout(() => {
        timedOut = true;
        child.kill("SIGKILL");
      }, timeoutMs);

      if (!child.stdout || !child.stderr) {
        finish(() => {
          reject(new ToolError("InternalError", "无法捕获 CLI 输出", { cliCode: "CLI_PIPE_FAILED" }));
        });
        return;
      }

      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk: string) => {
        stdoutBytes += Buffer.byteLength(chunk);
        if (stdoutBytes <= STDOUT_LIMIT_BYTES) {
          stdout += chunk;
        }
      });

      child.stderr.setEncoding("utf8");
      child.stderr.on("data", (chunk: string) => {
        stderrBytes += Buffer.byteLength(chunk);
        if (stderrBytes <= STDERR_LIMIT_BYTES) {
          stderr += chunk;
        }
      });

      child.on("error", (error) => {
        finish(() => {
          this.logger.error(`CLI 启动失败: ${error.message}`);
          reject(
            new ToolError("InternalError", `无法启动 Java CLI: ${error.message}`, {
              hint: "检查 FLP_I18N_JAVA 与 FLP_I18N_CLI_JAR 是否指向可执行文件",
              cliCode: "CLI_START_FAILED",
            }),
          );
        });
      });

      child.on("close", (code) => {
        finish(() => {
          const durationMs = Date.now() - startedAt;
          const trimmedStderr = stderr.trim();
          if (trimmedStderr) {
            this.logger.debug(`CLI stderr (${durationMs}ms): ${trimmedStderr}`);
          }

          if (timedOut) {
            this.logger.error(`CLI 执行超时 ${timeoutMs}ms: ${args[0] ?? ""}`);
            reject(
              new ToolError("UpstreamError", `CLI 执行超时（${timeoutMs}ms）`, {
                hint: "稍后重试；若反复超时，检查平台连通性或缩小模块范围",
                retryable: true,
                cliCode: "TIMEOUT",
              }),
            );
            return;
          }

          const exitCode = code ?? 1;
          let envelope: CliEnvelope;
          try {
            envelope = parseEnvelope(stdout);
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            this.logger.error(`CLI 输出解析失败 (exit=${exitCode}): ${message}`, stdout.slice(0, 2000));
            reject(
              new ToolError("UpstreamError", `CLI 输出不是合法 JSON: ${message}`, {
                hint: "确认 FLP_I18N_CLI_JAR 版本支持 --json",
                retryable: false,
                cliCode: "CLI_BAD_OUTPUT",
              }),
            );
            return;
          }

          if (exitCode !== 0 && envelope.error) {
            this.logger.info(`CLI 失败 exit=${exitCode} code=${envelope.error.code}: ${envelope.error.message}`);
          }

          resolve({
            exitCode,
            envelope,
            warnings: envelope.warnings,
            stderr: trimmedStderr,
            durationMs,
          });
        });
      });
    });
  }
}

function parseEnvelope(stdout: string): CliEnvelope {
  const text = stdout.trim();
  if (!text) {
    throw new Error("stdout 为空");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(message);
  }
  if (typeof parsed !== "object" || parsed === null) {
    throw new Error("输出不是 JSON 对象");
  }
  const candidate = parsed as Partial<CliEnvelope>;
  if (typeof candidate.ok !== "boolean" || typeof candidate.command !== "string") {
    throw new Error("缺少 ok/command 字段");
  }
  return {
    ok: candidate.ok,
    command: candidate.command,
    data: candidate.data ?? null,
    warnings: Array.isArray(candidate.warnings) ? candidate.warnings : [],
    error: candidate.error ?? null,
  };
}
