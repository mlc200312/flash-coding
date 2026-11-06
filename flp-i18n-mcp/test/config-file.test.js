"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = require("@modelcontextprotocol/sdk/inMemory.js");

const {
  ConfigError,
  ConfigStore,
  loadConfig,
  resolveConfig,
  writeUserConfig,
} = require("../dist/config.js");
const { createServer } = require("../dist/server.js");
const { CONFIG_YAML, makeTempDir, parseResult } = require("./helpers");

function paths(tmpDir) {
  return {
    jar: path.join(tmpDir, "cli.jar"),
    config: path.join(tmpDir, "modules.yaml"),
    userConfig: path.join(tmpDir, "user-config.json"),
  };
}

function envFor(tmpDir, overrides = {}) {
  const p = paths(tmpDir);
  fs.writeFileSync(p.jar, "");
  fs.writeFileSync(p.config, CONFIG_YAML);
  return {
    FLP_I18N_CLI_JAR: p.jar,
    FLP_I18N_CONFIG: p.config,
    FLP_I18N_ALLOWED_ROOTS: tmpDir,
    FLP_I18N_USER_CONFIG: p.userConfig,
    ...overrides,
  };
}

async function startConfigServer(store) {
  const { server } = createServer(store);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "config-test-client", version: "0.0.0" });
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return {
    client,
    async close() {
      await client.close();
      await server.close();
    },
  };
}

test("用户配置文件优先于环境变量，并标注来源", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir, { FLP_I18N_CLI_JAR: path.join(tmpDir, "missing.jar") });
  const p = paths(tmpDir);

  const state = resolveConfig(env, p.userConfig);
  assert.equal(state.config, null);
  assert.match(state.error.message, /missing\.jar/);

  writeUserConfig(p.userConfig, { cliJar: p.jar, configFile: p.config, allowedRoots: [tmpDir] });
  const merged = loadConfig(env);
  assert.equal(merged.command[2], p.jar);
  assert.equal(merged.configPath, p.config);

  const reloaded = resolveConfig(env, p.userConfig);
  assert.equal(reloaded.sources.cliJar, "file");
  assert.equal(reloaded.sources.configFile, "file");
});

test("配置文件 JSON 损坏时返回错误而不是抛出", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir);
  const p = paths(tmpDir);
  fs.writeFileSync(p.userConfig, "{ not json");

  const state = resolveConfig(env, p.userConfig);
  assert.equal(state.config, null);
  assert.ok(state.userConfigError);
  assert.ok(state.issues.some((issue) => issue.field === "file"));
  assert.equal(state.sources.cliJar, "env");
  assert.throws(() => loadConfig(env), ConfigError);
});

test("writeUserConfig 合并已有键并备份原文件", () => {
  const tmpDir = makeTempDir();
  const p = paths(tmpDir);
  writeUserConfig(p.userConfig, { java: "java", logLevel: "info" });
  const { backupPath } = writeUserConfig(p.userConfig, { logLevel: "debug" });

  const written = JSON.parse(fs.readFileSync(p.userConfig, "utf8"));
  assert.equal(written.java, "java");
  assert.equal(written.logLevel, "debug");
  assert.equal(written.version, 1);
  assert.ok(backupPath && fs.existsSync(backupPath));
});

test("ConfigStore 在配置文件变化后热加载", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir);
  const p = paths(tmpDir);
  const store = new ConfigStore(env, p.userConfig);

  assert.equal(store.getState().sources.cliJar, "env");
  writeUserConfig(p.userConfig, { java: "/opt/homebrew/bin/java" });
  const future = new Date(Date.now() + 1000);
  fs.utimesSync(p.userConfig, future, future);
  const next = store.getState();
  assert.equal(next.config.command[0], "/opt/homebrew/bin/java");
  assert.equal(next.sources.java, "file");
});

test("i18n_config_get 与 i18n_config_set 可在无有效配置时启动并完成配置", async () => {
  const tmpDir = makeTempDir();
  const env = {
    FLP_I18N_USER_CONFIG: path.join(tmpDir, "user-config.json"),
    HOME: tmpDir,
  };
  const p = paths(tmpDir);
  fs.writeFileSync(p.jar, "");
  fs.writeFileSync(p.config, CONFIG_YAML);
  const store = new ConfigStore(env, p.userConfig);
  const ctx = await startConfigServer(store);

  try {
    const before = parseResult(await ctx.client.callTool({ name: "i18n_config_get", arguments: {} }));
    assert.equal(before.ok, false);
    assert.equal(before.effective.ready, false);
    assert.match(before.error.message, /缺少/);

    const preview = parseResult(
      await ctx.client.callTool({
        name: "i18n_config_set",
        arguments: { cliJar: p.jar, configFile: p.config, allowedRoots: [tmpDir] },
      }),
    );
    assert.equal(preview.ok, true);
    assert.equal(preview.dryRun, true);
    assert.equal(fs.existsSync(p.userConfig), false);

    const applied = parseResult(
      await ctx.client.callTool({
        name: "i18n_config_set",
        arguments: {
          cliJar: p.jar,
          configFile: p.config,
          allowedRoots: [tmpDir],
          dryRun: false,
        },
      }),
    );
    assert.equal(applied.dryRun, false);
    assert.ok(applied.backupPath === null);
    assert.equal(applied.effective.ready, true);
    assert.equal(applied.effective.values.cliJar, p.jar);

    const after = parseResult(await ctx.client.callTool({ name: "i18n_config_get", arguments: {} }));
    assert.equal(after.ok, true);
    assert.equal(after.effective.sources.cliJar, "file");
  } finally {
    await ctx.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("cliJar 为空字符串时强制使用 Maven，忽略环境变量", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir);
  const p = paths(tmpDir);
  writeUserConfig(p.userConfig, { cliJar: "", configFile: p.config, allowedRoots: [tmpDir] });

  const state = resolveConfig(env, p.userConfig);
  assert.ok(state.config);
  assert.equal(state.config.cliJarMode, "maven");
  assert.match(state.config.command[2], /flp-i18n-tool-0\.1\.0\.jar$/);
});

test("i18n_config_set 传空 cliJar 可切回 Maven 模式", async () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir);
  const p = paths(tmpDir);
  writeUserConfig(p.userConfig, { cliJar: p.jar, configFile: p.config, allowedRoots: [tmpDir] });
  const store = new ConfigStore(env, p.userConfig);
  const ctx = await startConfigServer(store);

  try {
    const applied = parseResult(
      await ctx.client.callTool({
        name: "i18n_config_set",
        arguments: { cliJar: "", dryRun: false },
      }),
    );
    assert.equal(applied.ok, true);
    assert.equal(applied.effective.values.cliJarMode, "maven");
    const written = JSON.parse(fs.readFileSync(p.userConfig, "utf8"));
    assert.equal(written.cliJar, "");
  } finally {
    await ctx.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("i18n_config_set 拒绝不存在的路径与疑似 Token", async () => {
  const tmpDir = makeTempDir();
  const p = paths(tmpDir);
  const store = new ConfigStore({ FLP_I18N_USER_CONFIG: p.userConfig }, p.userConfig);
  const ctx = await startConfigServer(store);

  try {
    const missing = await ctx.client.callTool({
      name: "i18n_config_set",
      arguments: { cliJar: path.join(tmpDir, "nope.jar") },
    });
    assert.equal(missing.isError, true);
    assert.equal(parseResult(missing).error.code, "InvalidParams");

    const token = await ctx.client.callTool({
      name: "i18n_config_set",
      arguments: { configFile: "eyJhbGciOiJIUzI1NiJ9.payload.signature" },
    });
    assert.equal(token.isError, true);
    assert.match(parseResult(token).error.hint, /Token/);
  } finally {
    await ctx.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
