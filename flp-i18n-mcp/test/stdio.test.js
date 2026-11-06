"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { StdioClientTransport } = require("@modelcontextprotocol/sdk/client/stdio.js");

const { CONFIG_YAML, FIXTURE_CLI, REPO_ROOT, makeTempDir, parseResult } = require("./helpers");

function childEnv(extra) {
  const env = {};
  for (const [key, value] of Object.entries({ ...process.env, ...extra })) {
    if (typeof value === "string") {
      env[key] = value;
    }
  }
  return env;
}

test("stdio 集成：列出工具并调用 status/scan/diff，无 planId 的 upload 被拒绝", async () => {
  const tmpDir = makeTempDir();
  const configPath = path.join(tmpDir, "modules.yaml");
  const jarPath = path.join(tmpDir, "flp-i18n-cli.jar");
  const javaWrapper = path.join(tmpDir, "fake-java");
  const callLog = path.join(tmpDir, "calls.log");

  fs.writeFileSync(configPath, CONFIG_YAML);
  fs.writeFileSync(jarPath, "");
  fs.writeFileSync(
    javaWrapper,
    ["#!/bin/sh", "shift 2", `exec '${process.execPath}' '${FIXTURE_CLI}' "$@"`, ""].join("\n"),
  );
  fs.chmodSync(javaWrapper, 0o755);

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [path.join(REPO_ROOT, "dist", "index.js")],
    cwd: REPO_ROOT,
    stderr: "pipe",
    env: childEnv({
      FLP_I18N_JAVA: javaWrapper,
      FLP_I18N_CLI_JAR: jarPath,
      FLP_I18N_CONFIG: configPath,
      FLP_I18N_ALLOWED_ROOTS: tmpDir,
      FLP_I18N_USER_CONFIG: path.join(tmpDir, "user-config.json"),
      FLP_I18N_TOKEN: "test-token-abc12345",
      FLP_I18N_LOG_LEVEL: "error",
      FAKE_CALL_LOG: callLog,
    }),
  });

  const client = new Client({ name: "stdio-integration-test", version: "0.0.0" });

  try {
    await client.connect(transport);

    const { tools } = await client.listTools();
    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      [
        "i18n_config_get",
        "i18n_config_set",
        "i18n_diff",
        "i18n_download",
        "i18n_scan",
        "i18n_status",
        "i18n_upload",
        "i18n_validate",
      ],
    );

    const status = await client.callTool({ name: "i18n_status", arguments: {} });
    assert.equal(parseResult(status).ok, true);

    const scan = await client.callTool({ name: "i18n_scan", arguments: { module: "demo" } });
    assert.equal(parseResult(scan).data.counts.total, 3);

    const diff = await client.callTool({ name: "i18n_diff", arguments: { module: "demo" } });
    assert.equal(parseResult(diff).data.totals.missingOnPlatform, 2);

    const upload = await client.callTool({
      name: "i18n_upload",
      arguments: { module: "demo", dryRun: false },
    });
    assert.equal(upload.isError, true);
    assert.equal(parseResult(upload).error.code, "ConfirmationRequired");

    const { resources } = await client.listResources();
    assert.ok(resources.some((resource) => resource.uri === "i18n://reports/latest"));
  } finally {
    await client.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
