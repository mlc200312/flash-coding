"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { ConfigError, loadConfig } = require("../dist/config.js");
const { makeTempDir } = require("./helpers");

function envFor(tmpDir, overrides = {}) {
  const jar = path.join(tmpDir, "cli.jar");
  const config = path.join(tmpDir, "modules.yaml");
  fs.writeFileSync(jar, "");
  fs.writeFileSync(config, "version: 1\n");
  return {
    FLP_I18N_CLI_JAR: jar,
    FLP_I18N_CONFIG: config,
    FLP_I18N_ALLOWED_ROOTS: [tmpDir, path.dirname(tmpDir)].join(path.delimiter),
    ...overrides,
  };
}

test("loadConfig 读取必需变量并拼出 java -jar 命令", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir, {
    FLP_I18N_TOKEN: "  token-value-123  ",
    FLP_I18N_LOG_LEVEL: "debug",
    FLP_I18N_JAVA: "/opt/java/bin/java",
  });

  const config = loadConfig(env);

  assert.deepEqual(config.command, ["/opt/java/bin/java", "-jar", path.join(tmpDir, "cli.jar")]);
  assert.equal(config.configPath, path.join(tmpDir, "modules.yaml"));
  assert.deepEqual(config.allowedRoots, [tmpDir, path.dirname(tmpDir)]);
  assert.equal(config.token, "token-value-123");
  assert.equal(config.logLevel, "debug");
  assert.equal(config.readTimeoutMs, 120000);
  assert.equal(config.writeTimeoutMs, 600000);
});

test("loadConfig 默认 java、info 日志、无 Token", () => {
  const config = loadConfig(envFor(makeTempDir()));
  assert.equal(config.command[0], "java");
  assert.equal(config.token, null);
  assert.equal(config.logLevel, "info");
});

test("loadConfig 缺少必需变量时报 ConfigError", () => {
  const tmpDir = makeTempDir();
  const full = envFor(tmpDir);

  for (const name of ["FLP_I18N_CLI_JAR", "FLP_I18N_CONFIG", "FLP_I18N_ALLOWED_ROOTS"]) {
    const env = { ...full };
    delete env[name];
    assert.throws(() => loadConfig(env), (error) => error instanceof ConfigError && error.message.includes(name));
  }
});

test("loadConfig 校验文件存在与日志级别", () => {
  const tmpDir = makeTempDir();

  assert.throws(
    () => loadConfig(envFor(tmpDir, { FLP_I18N_CLI_JAR: path.join(tmpDir, "missing.jar") })),
    ConfigError,
  );

  assert.throws(() => loadConfig(envFor(tmpDir, { FLP_I18N_LOG_LEVEL: "verbose" })), ConfigError);
  assert.throws(() => loadConfig(envFor(tmpDir, { FLP_I18N_ALLOWED_ROOTS: "  " })), ConfigError);
});
