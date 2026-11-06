#!/usr/bin/env node
"use strict";

const fs = require("node:fs");

const PLAN_ID = "a".repeat(64);
const argv = process.argv.slice(2);
const flags = parseFlags(argv);
const command = argv.find((arg) => !arg.startsWith("-"));
const moduleName = flags.module || "demo";
const os = flags.os || "flp-code";

const mode = process.env.FAKE_MODE || "";
const delayMs = Number(process.env.FAKE_DELAY_MS || 0);

log("start");

const wait = delayMs > 0 ? () => new Promise((resolve) => setTimeout(resolve, delayMs)) : async () => {};

main().then(
  (exitCode) => {
    log("end", { exitCode });
    process.exitCode = exitCode;
  },
  (error) => {
    log("end", { exitCode: 1, error: String(error) });
    process.exitCode = 1;
  },
);

async function main() {
  await wait();

  if (mode === "timeout") {
    await new Promise(() => {});
  }
  if (mode === "bad-json") {
    process.stdout.write("not json\n");
    return 0;
  }

  switch (command) {
    case "status":
      return status();
    case "scan":
      return success("scan", {
        module: moduleName,
        os,
        repoRoot: `/tmp/${moduleName}`,
        counts: { codeKeys: 2, enumKeys: 1, total: 3 },
        codeKeys: [`${moduleName}_1`, `${moduleName}_2`],
        enumKeys: [`SampleEnum_1`],
        duplicates: [],
        ok: true,
      });
    case "diff":
      if (mode === "remote-down") {
        return failure(3, "diff", { module: moduleName, os }, {
          code: "REMOTE_UNAVAILABLE",
          message: "字典下载失败: connection refused",
          hint: "检查网络或稍后重试",
          retryable: true,
        });
      }
      return success("diff", {
        module: moduleName,
        os,
        ok: true,
        languages: { zh_CN: { missingOnPlatform: 2, missingLocally: 0 }, en: { missingOnPlatform: 2 } },
        totals: { missingOnPlatform: 2, missingLocally: 0, zhConflicts: 0 },
        remoteUnavailable: [],
      });
    case "validate":
      return success("validate", {
        module: moduleName,
        ok: true,
        valid: true,
        keysChecked: 3,
        errorCount: 0,
        warningCount: 0,
        issues: [],
      });
    case "upload":
      return writeCommand("upload", {
        plan: {
          planId: PLAN_ID,
          entryCount: 2,
          moduleCount: 1,
          entries: [
            { module: moduleName, os, key: `${moduleName}_1`, language: "zh_CN" },
            { module: moduleName, os, key: `${moduleName}_2`, language: "zh_CN" },
          ],
        },
        applied: 2,
        failed: 0,
        remaining: 0,
      });
    case "download":
      return writeCommand("download", {
        plan: {
          planId: PLAN_ID,
          fileCount: 1,
          operationCount: 2,
          files: [`${moduleName}/messages_zh_CN.properties`],
        },
        changedFiles: [`${moduleName}/messages_zh_CN.properties`],
        appliedOperations: 2,
        backupDir: "/tmp/backup",
      });
    default:
      return failure(2, command || "unknown", null, {
        code: "INVALID_ARGUMENT",
        message: `未知命令: ${command}`,
        hint: "使用 status|scan|diff|validate|upload|download",
        retryable: false,
      });
  }
}

function status() {
  if (mode === "token-missing") {
    return failure(2, "status", {
      configOk: true,
      configPath: "/tmp/modules.yaml",
      token: { present: false, jwt: false, expiresAt: null },
      remote: { cdnReachable: true, platformReachable: true },
    }, {
      code: "TOKEN_MISSING",
      message: "未检测到环境变量 FLP_I18N_TOKEN",
      hint: "export FLP_I18N_TOKEN=<token> 后重试",
      retryable: false,
    });
  }
  return success("status", {
    configOk: true,
    configPath: "/tmp/modules.yaml",
    moduleCount: 1,
    modules: ["demo"],
    languages: ["zh_CN", "en"],
    token: { present: true, jwt: true, expiresAt: "2099-01-01T00:00:00Z" },
    remote: { cdnReachable: true, platformReachable: true },
  });
}

function writeCommand(name, appliedData) {
  const apply = flags.apply === true;
  if (!apply) {
    return success(name, {
      planId: PLAN_ID,
      plan: appliedData.plan,
      apply: false,
      alreadyCompleted: false,
    });
  }
  if (mode === "git-dirty") {
    return failure(4, name, { module: moduleName, os }, {
      code: "GIT_DIRTY",
      message: "工作区存在未提交改动",
      hint: "先提交或 stash 后重试；禁止使用 --force 绕过",
      retryable: false,
    });
  }
  if (flags["plan-id"] !== PLAN_ID) {
    return failure(4, name, { module: moduleName, os }, {
      code: "PLAN_STALE",
      message: "planId 与当前计划不一致",
      hint: "重新 dry-run 获取最新 planId",
      retryable: false,
    });
  }
  return success(name, {
    planId: PLAN_ID,
    plan: appliedData.plan,
    apply: true,
    ...appliedData,
  });
}

function success(commandName, data, warnings = []) {
  emit({ ok: true, command: commandName, data, warnings, error: null });
  return 0;
}

function failure(exitCode, commandName, data, error) {
  emit({ ok: false, command: commandName, data, warnings: [], error });
  return exitCode;
}

function emit(envelope) {
  process.stdout.write(`${JSON.stringify(envelope)}\n`);
}

function parseFlags(args) {
  const result = {};
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (!arg.startsWith("-")) {
      continue;
    }
    const name = arg.replace(/^--?/, "");
    const next = args[i + 1];
    if (name === "apply" || name === "force" || name === "json") {
      result[name] = true;
    } else if (next !== undefined && !next.startsWith("-")) {
      result[name] = next;
      i += 1;
    }
  }
  return result;
}

function log(event, extra = {}) {
  const file = process.env.FAKE_CALL_LOG;
  if (!file) {
    return;
  }
  const entry = {
    event,
    argv,
    command,
    module: moduleName,
    os,
    pid: process.pid,
    time: Date.now(),
    mode,
    ...extra,
  };
  fs.appendFileSync(file, `${JSON.stringify(entry)}\n`);
}
