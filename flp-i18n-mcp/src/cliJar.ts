import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import type { ServerConfig } from "./config";
import { ToolError } from "./errors";
import type { Logger } from "./logger";

const MAX_JAR_BYTES = 256 * 1024 * 1024;
const DOWNLOAD_TIMEOUT_MS = 120_000;
const CHECKSUM_TIMEOUT_MS = 30_000;
const COORD_PART = /^[A-Za-z0-9_.-]+$/;

export interface MavenCoordinate {
  groupId: string;
  artifactId: string;
  version: string;
  classifier: string | null;
}

export class CliJarResolver {
  constructor(
    private readonly logger: Logger,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly downloadTimeoutMs: number = DOWNLOAD_TIMEOUT_MS,
  ) {}

  async ensure(config: ServerConfig): Promise<string> {
    const jarPath = config.cliJar ?? config.command[2];
    if ((config.cliJarMode ?? "path") !== "maven") {
      if (!jarPath || !isFile(jarPath)) {
        throw new ToolError("InternalError", `CLI jar 不存在: ${jarPath ?? "(未配置)"}`, {
          hint: "检查 FLP_I18N_CLI_JAR 或用户配置的 cliJar；也可留空 jar 改用 Maven 坐标自动下载",
          cliCode: "CLI_UNAVAILABLE",
        });
      }
      return jarPath;
    }

    if (jarPath && isFile(jarPath)) {
      return jarPath;
    }

    if (!config.cliMavenRepo || !config.cliMavenCoordinate) {
      throw new ToolError("InternalError", "Maven 自动下载缺少仓库或坐标", {
        hint: "设置 FLP_I18N_CLI_MAVEN_REPO 与 FLP_I18N_CLI_MAVEN_COORD",
        cliCode: "CLI_MAVEN_CONFIG_MISSING",
      });
    }

    const coordinate = parseMavenCoordinate(config.cliMavenCoordinate);
    if (!coordinate) {
      throw new ToolError("InvalidParams", `Maven 坐标非法: ${config.cliMavenCoordinate}`, {
        hint: "坐标格式为 groupId:artifactId:version[:classifier]",
        cliCode: "CLI_MAVEN_COORDINATE_INVALID",
      });
    }

    const target = jarPath ?? cacheJarPath(config.cacheDir ?? defaultCacheDir(), coordinate);
    await downloadVerifiedJar(config.cliMavenRepo, coordinate, target, this.fetchImpl, this.downloadTimeoutMs);
    this.logger.info(`已从 Maven 下载 CLI jar: ${target}`);
    return target;
  }
}

export function parseMavenCoordinate(raw: string): MavenCoordinate | null {
  const parts = raw.trim().split(":");
  if (parts.length !== 3 && parts.length !== 4) {
    return null;
  }
  const [groupId, artifactId, version, classifier] = parts;
  for (const part of parts) {
    if (!part || !COORD_PART.test(part) || part.includes("..")) {
      return null;
    }
  }
  if (/-SNAPSHOT$/i.test(version)) {
    return null;
  }
  if (classifier !== undefined && !classifier) {
    return null;
  }
  return { groupId, artifactId, version, classifier: classifier ?? null };
}

export function cacheJarPath(cacheDir: string, coordinate: MavenCoordinate): string {
  const base = path.resolve(cacheDir);
  const fileName = `${coordinate.artifactId}-${coordinate.version}${
    coordinate.classifier ? `-${coordinate.classifier}` : ""
  }.jar`;
  const relative = path.join(
    ...coordinate.groupId.split("."),
    coordinate.artifactId,
    coordinate.version,
    fileName,
  );
  const resolved = path.resolve(base, relative);
  if (!resolved.startsWith(`${base}${path.sep}`)) {
    throw new ToolError("InvalidParams", `Maven 坐标路径非法: ${coordinate.groupId}`, {
      cliCode: "CLI_MAVEN_COORDINATE_INVALID",
    });
  }
  return resolved;
}

export function mavenJarUrl(repo: string, coordinate: MavenCoordinate): string {
  const fileName = `${coordinate.artifactId}-${coordinate.version}${
    coordinate.classifier ? `-${coordinate.classifier}` : ""
  }.jar`;
  const segments = [
    ...coordinate.groupId.split("."),
    coordinate.artifactId,
    coordinate.version,
    fileName,
  ];
  return `${repo.replace(/\/+$/, "")}/${segments.join("/")}`;
}

export function defaultCacheDir(env: NodeJS.ProcessEnv = process.env): string {
  const explicit = (env.FLP_I18N_CACHE_DIR ?? "").trim();
  if (explicit && !explicit.includes("${")) {
    return path.resolve(explicit);
  }
  const xdg = (env.XDG_CACHE_HOME ?? "").trim();
  if (xdg && !xdg.includes("${")) {
    return path.join(xdg, "flp-i18n", "cli");
  }
  return path.join(env.HOME || process.env.HOME || os.homedir(), ".cache", "flp-i18n", "cli");
}

async function downloadVerifiedJar(
  repo: string,
  coordinate: MavenCoordinate,
  target: string,
  fetchImpl: typeof fetch,
  timeoutMs: number,
): Promise<void> {
  const url = mavenJarUrl(repo, coordinate);
  const bytes = await fetchBytes(url, fetchImpl, timeoutMs);
  const { algorithm, expected } = await fetchChecksum(url, fetchImpl);
  const actual = createHash(algorithm).update(bytes).digest("hex");
  if (actual.toLowerCase() !== expected.toLowerCase()) {
    throw new ToolError("UpstreamError", `CLI jar 校验失败: ${url}`, {
      hint: `期望 ${algorithm} ${expected}，实际 ${actual}；已放弃写入，请检查仓库完整性`,
      retryable: false,
      cliCode: "CLI_CHECKSUM_MISMATCH",
    });
  }

  fs.mkdirSync(path.dirname(target), { recursive: true });
  const tmp = `${target}.part-${process.pid}-${Date.now()}`;
  try {
    fs.writeFileSync(tmp, bytes, { mode: 0o644 });
    fs.renameSync(tmp, target);
  } catch (error) {
    fs.rmSync(tmp, { force: true });
    throw new ToolError("InternalError", `无法写入 CLI jar 缓存: ${target}`, {
      hint: describeError(error),
      cliCode: "CLI_CACHE_WRITE_FAILED",
    });
  }
}

async function fetchChecksum(
  url: string,
  fetchImpl: typeof fetch,
): Promise<{ algorithm: "sha256" | "sha1"; expected: string }> {
  for (const [suffix, algorithm, length] of [
    [".sha256", "sha256", 64],
    [".sha1", "sha1", 40],
  ] as const) {
    const response = await request(`${url}${suffix}`, fetchImpl, CHECKSUM_TIMEOUT_MS);
    if (response.status === 404) {
      continue;
    }
    if (!response.ok) {
      throw new ToolError("UpstreamError", `无法获取 ${algorithm} 校验文件: ${response.status}`, {
        hint: `${url}${suffix}`,
        retryable: response.status >= 500,
        cliCode: "CLI_CHECKSUM_FETCH_FAILED",
      });
    }
    const text = await response.text();
    const match = text.trim().match(new RegExp(`[A-Fa-f0-9]{${length}}`));
    if (!match) {
      throw new ToolError("UpstreamError", `${algorithm} 校验文件格式非法: ${url}${suffix}`, {
        retryable: false,
        cliCode: "CLI_CHECKSUM_INVALID",
      });
    }
    return { algorithm, expected: match[0] };
  }

  throw new ToolError("UpstreamError", `Maven 仓库未提供校验文件: ${url}`, {
    hint: "需要 .sha256 或 .sha1，拒绝下载未校验的 jar",
    retryable: false,
    cliCode: "CLI_CHECKSUM_MISSING",
  });
}

async function fetchBytes(url: string, fetchImpl: typeof fetch, timeoutMs: number): Promise<Buffer> {
  const response = await request(url, fetchImpl, timeoutMs);
  if (!response.ok) {
    throw new ToolError("UpstreamError", `下载 CLI jar 失败: ${response.status} ${url}`, {
      hint: "确认 Maven 仓库地址、坐标与网络连通性",
      retryable: response.status >= 500,
      cliCode: "CLI_DOWNLOAD_FAILED",
    });
  }
  const declared = Number(response.headers.get("content-length") ?? "0");
  if (declared > MAX_JAR_BYTES) {
    throw new ToolError("UpstreamError", `CLI jar 体积异常: ${declared} bytes`, {
      retryable: false,
      cliCode: "CLI_DOWNLOAD_TOO_LARGE",
    });
  }
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.byteLength === 0 || buffer.byteLength > MAX_JAR_BYTES) {
    throw new ToolError("UpstreamError", `CLI jar 体积异常: ${buffer.byteLength} bytes`, {
      retryable: false,
      cliCode: "CLI_DOWNLOAD_TOO_LARGE",
    });
  }
  return buffer;
}

async function request(url: string, fetchImpl: typeof fetch, timeoutMs: number): Promise<Response> {
  try {
    return await fetchImpl(url, {
      redirect: "follow",
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    throw new ToolError("UpstreamError", `无法访问 Maven 仓库: ${url}`, {
      hint: `${describeError(error)}；检查网络、代理与仓库地址`,
      retryable: true,
      cliCode: "CLI_DOWNLOAD_FAILED",
    });
  }
}

function isFile(filePath: string): boolean {
  try {
    return fs.statSync(filePath).isFile();
  } catch {
    return false;
  }
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
