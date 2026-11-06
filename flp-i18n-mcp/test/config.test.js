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
    FLP_I18N_USER_CONFIG: path.join(tmpDir, "user-config.json"),
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

  for (const name of ["FLP_I18N_CONFIG", "FLP_I18N_ALLOWED_ROOTS"]) {
    const env = { ...full };
    delete env[name];
    assert.throws(() => loadConfig(env), (error) => error instanceof ConfigError && error.message.includes(name));
  }
});

test("未配置 jar 时默认按 Maven 坐标解析缓存路径", () => {
  const tmpDir = makeTempDir();
  const env = envFor(tmpDir, { FLP_I18N_CACHE_DIR: path.join(tmpDir, "cache") });
  delete env.FLP_I18N_CLI_JAR;

  const config = loadConfig(env);

  assert.equal(config.cliJarMode, "maven");
  assert.equal(config.cliMavenRepo, "https://registry.flashexpress.pub/repository/maven-public");
  assert.equal(config.cliMavenCoordinate, "com.flashfin:flp-i18n-tool:0.1.0");
  assert.equal(
    config.cliJar,
    path.join(tmpDir, "cache", "com", "flashfin", "flp-i18n-tool", "0.1.0", "flp-i18n-tool-0.1.0.jar"),
  );
  assert.match(config.command[2], /flp-i18n-tool-0\.1\.0\.jar$/);
});

test("Maven 坐标与仓库非法时报 ConfigError", () => {
  const tmpDir = makeTempDir();
  const base = envFor(tmpDir);
  delete base.FLP_I18N_CLI_JAR;

  assert.throws(
    () => loadConfig({ ...base, FLP_I18N_CLI_MAVEN_COORD: "a:b" }),
    ConfigError,
  );
  assert.throws(
    () => loadConfig({ ...base, FLP_I18N_CLI_MAVEN_COORD: "g:a:0.1.0-SNAPSHOT" }),
    ConfigError,
  );
  assert.throws(
    () => loadConfig({ ...base, FLP_I18N_CLI_MAVEN_REPO: "file:///tmp/repo" }),
    ConfigError,
  );
  assert.throws(
    () => loadConfig({ ...base, FLP_I18N_CLI_MAVEN_REPO: "https://user:pass@example.com/repo" }),
    ConfigError,
  );
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
