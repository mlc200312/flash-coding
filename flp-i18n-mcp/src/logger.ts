import { inspect } from "node:util";

import type { LogLevel } from "./config";

const LEVEL_ORDER: Record<LogLevel, number> = { error: 0, info: 1, debug: 2 };

export type Redactor = (text: string) => string;

const REDACTED = "[REDACTED]";

const BEARER_PATTERN = /(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi;
const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g;
const SECRET_FIELD_PATTERN =
  /((?:^|[^A-Za-z0-9_])(?:token|access_token|appSecret|app_secret|password|authorization)["']?\s*[:=]\s*["']?)([^"'\s,;}\]]+)/gi;

export function createRedactor(secrets: Array<string | null | undefined>): Redactor {
  const literals = secrets
    .filter((value): value is string => typeof value === "string" && value.trim().length >= 4)
    .map((value) => value.trim());

  return (text: string): string => {
    let result = text;
    for (const literal of literals) {
      result = result.split(literal).join(REDACTED);
    }
    return result
      .replace(BEARER_PATTERN, `$1${REDACTED}`)
      .replace(JWT_PATTERN, "[REDACTED_JWT]")
      .replace(SECRET_FIELD_PATTERN, (match, prefix: string, value: string) =>
        value.startsWith("[") || /^(?:Bearer|Basic)$/i.test(value) ? match : `${prefix}${REDACTED}`,
      );
  };
}

export class Logger {
  private readonly threshold: number;

  constructor(
    private readonly level: LogLevel,
    private readonly redact: Redactor = (text) => text,
  ) {
    this.threshold = LEVEL_ORDER[level];
  }

  error(message: string, detail?: unknown): void {
    this.write("error", message, detail);
  }

  info(message: string, detail?: unknown): void {
    this.write("info", message, detail);
  }

  debug(message: string, detail?: unknown): void {
    this.write("debug", message, detail);
  }

  private write(level: LogLevel, message: string, detail?: unknown): void {
    if (LEVEL_ORDER[level] > this.threshold) {
      return;
    }
    let line = `[flp-i18n-mcp] ${level.toUpperCase()} ${message}`;
    if (detail !== undefined) {
      line += ` ${formatDetail(detail)}`;
    }
    process.stderr.write(`${this.redact(line)}\n`);
  }
}

function formatDetail(detail: unknown): string {
  if (detail instanceof Error) {
    return detail.stack ?? detail.message;
  }
  if (typeof detail === "string") {
    return detail;
  }
  return inspect(detail, { depth: 4, breakLength: 120 });
}
