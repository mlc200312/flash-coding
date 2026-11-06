"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");

const {
  makeConfig,
  makeTempDir,
  parseResult,
  readJsonLines,
  startServer,
  withEnv,
} = require("./helpers");

const PLAN_ID = "a".repeat(64);
const CLI_TOOLS = [
  "i18n_diff",
  "i18n_download",
  "i18n_scan",
  "i18n_status",
  "i18n_upload",
  "i18n_validate",
];

test("列出六个工具，写工具包含 dryRun 默认值与 planId", async () => {
  const config = makeConfig();
  const ctx = await startServer(config);
  try {
    const { tools } = await ctx.client.listTools();
    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      CLI_TOOLS,
    );

    const upload = tools.find((tool) => tool.name === "i18n_upload");
    assert.ok(upload.inputSchema.properties.dryRun);
    assert.equal(upload.inputSchema.properties.dryRun.default, true);
    assert.ok(upload.inputSchema.properties.planId);
    assert.ok(upload.inputSchema.required.includes("module"));
    assert.equal(upload.inputSchema.required.includes("dryRun"), false);
    assert.equal(upload.inputSchema.properties.planId.pattern, "^[0-9a-fA-F]{64}$");

    const download = tools.find((tool) => tool.name === "i18n_download");
    assert.equal(download.inputSchema.properties.dryRun.default, true);
    assert.ok(download.inputSchema.properties.languages);

    const status = tools.find((tool) => tool.name === "i18n_status");
    assert.deepEqual(Object.keys(status.inputSchema.properties ?? {}), []);
  } finally {
    await ctx.close();
  }
});

test("status 成功并返回配置与远端状态", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await ctx.client.callTool({ name: "i18n_status", arguments: {} });
    assert.notEqual(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.ok, true);
    assert.equal(payload.command, "status");
    assert.equal(payload.data.configOk, true);
    assert.equal(payload.data.remote.platformReachable, true);
  } finally {
    await ctx.close();
  }
});

test("status 在 Token 缺失时仍返回诊断信息而不报工具错误", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await withEnv({ FAKE_MODE: "token-missing" }, () =>
      ctx.client.callTool({ name: "i18n_status", arguments: {} }),
    );
    assert.notEqual(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.ok, false);
    assert.equal(payload.error.code, "TOKEN_MISSING");
    assert.equal(payload.data.token.present, false);
  } finally {
    await ctx.close();
  }
});

test("scan 与 diff 传递 module/os/languages 参数", async () => {
  const tmpDir = makeTempDir();
  const logFile = path.join(tmpDir, "calls.log");
  const ctx = await startServer(makeConfig({ tmpDir }));
  try {
    const scan = await withEnv({ FAKE_CALL_LOG: logFile }, () =>
      ctx.client.callTool({ name: "i18n_scan", arguments: { module: "demo", os: "flp-enum" } }),
    );
    const scanPayload = parseResult(scan);
    assert.equal(scanPayload.ok, true);
    assert.equal(scanPayload.data.os, "flp-enum");

    const diff = await withEnv({ FAKE_CALL_LOG: logFile }, () =>
      ctx.client.callTool({
        name: "i18n_diff",
        arguments: { module: "demo", os: "flp-enum", languages: ["zh_CN", "en"] },
      }),
    );
    assert.equal(parseResult(diff).ok, true);

    const entries = readJsonLines(logFile).filter((entry) => entry.event === "end");
    assert.equal(entries.length, 2);
    assert.ok(entries[0].argv.includes("--module"));
    assert.ok(entries[0].argv.includes("flp-enum"));
    assert.ok(entries[1].argv.includes("--languages"));
    assert.ok(entries[1].argv.includes("zh_CN,en"));
  } finally {
    await ctx.close();
  }
});

test("validate 成功返回 issue 列表", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await ctx.client.callTool({ name: "i18n_validate", arguments: { module: "demo" } });
    const payload = parseResult(result);
    assert.equal(payload.ok, true);
    assert.equal(payload.data.valid, true);
    assert.deepEqual(payload.data.issues, []);
  } finally {
    await ctx.close();
  }
});

test("upload 无 planId 时返回 ConfirmationRequired 且不执行 CLI", async () => {
  const tmpDir = makeTempDir();
  const logFile = path.join(tmpDir, "calls.log");
  const ctx = await startServer(makeConfig({ tmpDir }));
  try {
    const result = await withEnv({ FAKE_CALL_LOG: logFile }, () =>
      ctx.client.callTool({ name: "i18n_upload", arguments: { module: "demo", dryRun: false } }),
    );
    assert.equal(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.error.code, "ConfirmationRequired");
    assert.equal(payload.error.cliCode, "PLAN_ID_REQUIRED");
    assert.match(payload.error.hint, /planId/);
    assert.deepEqual(readJsonLines(logFile), []);
  } finally {
    await ctx.close();
  }
});

test("upload 完整流程：dryRun 生成 planId，apply 成功后返回 applied", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const dry = await ctx.client.callTool({ name: "i18n_upload", arguments: { module: "demo" } });
    const dryPayload = parseResult(dry);
    assert.equal(dryPayload.ok, true);
    assert.equal(dryPayload.data.planId, PLAN_ID);
    assert.equal(dryPayload.data.apply, false);
    assert.equal(dryPayload.data.plan.entryCount, 2);

    const applied = await ctx.client.callTool({
      name: "i18n_upload",
      arguments: { module: "demo", dryRun: false, planId: PLAN_ID, limit: 10, rateMs: 0 },
    });
    const appliedPayload = parseResult(applied);
    assert.equal(appliedPayload.ok, true);
    assert.equal(appliedPayload.data.apply, true);
    assert.equal(appliedPayload.data.applied, 2);
  } finally {
    await ctx.close();
  }
});

test("upload 使用错误 planId 时返回 ConfirmationRequired(PLAN_STALE)", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await ctx.client.callTool({
      name: "i18n_upload",
      arguments: { module: "demo", dryRun: false, planId: "f".repeat(64) },
    });
    assert.equal(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.error.code, "ConfirmationRequired");
    assert.equal(payload.error.cliCode, "PLAN_STALE");
  } finally {
    await ctx.close();
  }
});

test("upload/git 脏工作区返回 ConfirmationRequired(GIT_DIRTY)", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await withEnv({ FAKE_MODE: "git-dirty" }, () =>
      ctx.client.callTool({
        name: "i18n_upload",
        arguments: { module: "demo", dryRun: false, planId: PLAN_ID },
      }),
    );
    const payload = parseResult(result);
    assert.equal(payload.error.code, "ConfirmationRequired");
    assert.equal(payload.error.cliCode, "GIT_DIRTY");
  } finally {
    await ctx.close();
  }
});

test("dryRun=true 同时携带 planId 返回 InvalidParams 且不执行 CLI", async () => {
  const tmpDir = makeTempDir();
  const logFile = path.join(tmpDir, "calls.log");
  const ctx = await startServer(makeConfig({ tmpDir }));
  try {
    const result = await withEnv({ FAKE_CALL_LOG: logFile }, () =>
      ctx.client.callTool({
        name: "i18n_download",
        arguments: { module: "demo", dryRun: true, planId: PLAN_ID },
      }),
    );
    assert.equal(result.isError, true);
    assert.equal(parseResult(result).error.code, "InvalidParams");
    assert.deepEqual(readJsonLines(logFile), []);
  } finally {
    await ctx.close();
  }
});

test("download dryRun 与 apply 返回文件与操作摘要", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const dry = await ctx.client.callTool({
      name: "i18n_download",
      arguments: { module: "demo", languages: ["zh_CN"] },
    });
    const dryPayload = parseResult(dry);
    assert.equal(dryPayload.ok, true);
    assert.equal(dryPayload.data.plan.fileCount, 1);

    const applied = await ctx.client.callTool({
      name: "i18n_download",
      arguments: { module: "demo", dryRun: false, planId: PLAN_ID },
    });
    const appliedPayload = parseResult(applied);
    assert.equal(appliedPayload.ok, true);
    assert.equal(appliedPayload.data.apply, true);
    assert.equal(appliedPayload.data.appliedOperations, 2);
  } finally {
    await ctx.close();
  }
});

test("同一 module 的写操作串行执行", async () => {
  const tmpDir = makeTempDir();
  const logFile = path.join(tmpDir, "calls.log");
  const ctx = await startServer(makeConfig({ tmpDir }));
  try {
    const [first, second] = await withEnv({ FAKE_CALL_LOG: logFile, FAKE_DELAY_MS: "120" }, () =>
      Promise.all([
        ctx.client.callTool({ name: "i18n_upload", arguments: { module: "demo", os: "flp-code" } }),
        ctx.client.callTool({ name: "i18n_upload", arguments: { module: "demo", os: "flp-enum" } }),
      ]),
    );
    assert.equal(parseResult(first).ok, true);
    assert.equal(parseResult(second).ok, true);

    const ranges = new Map();
    for (const entry of readJsonLines(logFile)) {
      const range = ranges.get(entry.pid) ?? {};
      range[entry.event] = entry.time;
      ranges.set(entry.pid, range);
    }
    const intervals = [...ranges.values()].sort((a, b) => a.start - b.start);
    assert.equal(intervals.length, 2);
    assert.ok(
      intervals[0].end <= intervals[1].start,
      `第二次写操作应在第一次结束后开始: ${JSON.stringify(intervals)}`,
    );
  } finally {
    await ctx.close();
  }
});

test("CLI 远端错误映射为 UpstreamError 且保留 retryable", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await withEnv({ FAKE_MODE: "remote-down" }, () =>
      ctx.client.callTool({ name: "i18n_diff", arguments: { module: "demo" } }),
    );
    assert.equal(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.error.code, "UpstreamError");
    assert.equal(payload.error.cliCode, "REMOTE_UNAVAILABLE");
    assert.equal(payload.error.retryable, true);
  } finally {
    await ctx.close();
  }
});

test("CLI 超时映射为 retryable UpstreamError", async () => {
  const config = makeConfig({ readTimeoutMs: 150 });
  const ctx = await startServer(config);
  try {
    const result = await withEnv({ FAKE_DELAY_MS: "1500" }, () =>
      ctx.client.callTool({ name: "i18n_scan", arguments: { module: "demo" } }),
    );
    assert.equal(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.error.code, "UpstreamError");
    assert.equal(payload.error.cliCode, "TIMEOUT");
    assert.equal(payload.error.retryable, true);
    assert.match(payload.error.message, /超时/);
  } finally {
    await ctx.close();
  }
});

test("CLI 输出非 JSON 时返回 UpstreamError(CLI_BAD_OUTPUT)", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const result = await withEnv({ FAKE_MODE: "bad-json" }, () =>
      ctx.client.callTool({ name: "i18n_scan", arguments: { module: "demo" } }),
    );
    assert.equal(result.isError, true);
    const payload = parseResult(result);
    assert.equal(payload.error.code, "UpstreamError");
    assert.equal(payload.error.cliCode, "CLI_BAD_OUTPUT");
  } finally {
    await ctx.close();
  }
});
