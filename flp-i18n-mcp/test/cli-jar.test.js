"use strict";

const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const {
  CliJarResolver,
  cacheJarPath,
  mavenJarUrl,
  parseMavenCoordinate,
} = require("../dist/cliJar.js");
const { CliRunner } = require("../dist/cli.js");
const { StaticConfigStore } = require("../dist/config.js");
const { Logger } = require("../dist/logger.js");
const { FIXTURE_CLI, makeTempDir } = require("./helpers");

const COORDINATE = "com.example:demo:1.0.0";
const JAR_BYTES = Buffer.from("fake jar content for maven resolution");

function sha(algorithm, bytes) {
  return createHash(algorithm).update(bytes).digest("hex");
}

async function startRepo(routes) {
  const server = http.createServer((request, response) => {
    const body = routes[request.url];
    if (body === undefined) {
      response.statusCode = 404;
      response.end("not found");
      return;
    }
    response.statusCode = 200;
    response.end(body);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}/repo`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function mavenRoutes(overrides = {}) {
  const jarPath = "/repo/com/example/demo/1.0.0/demo-1.0.0.jar";
  return {
    [jarPath]: JAR_BYTES,
    [`${jarPath}.sha1`]: sha("sha1", JAR_BYTES),
    ...overrides,
  };
}

function mavenConfig(tmpDir, repo, overrides = {}) {
  const coordinate = parseMavenCoordinate(COORDINATE);
  const cliJar = cacheJarPath(path.join(tmpDir, "cache"), coordinate);
  return {
    command: ["java", "-jar", cliJar],
    configPath: path.join(tmpDir, "modules.yaml"),
    allowedRoots: [tmpDir],
    token: null,
    logLevel: "error",
    readTimeoutMs: 5000,
    writeTimeoutMs: 10000,
    cliJar,
    cliJarMode: "maven",
    cliMavenRepo: repo,
    cliMavenCoordinate: COORDINATE,
    cacheDir: path.join(tmpDir, "cache"),
    ...overrides,
  };
}

test("Maven 坐标、缓存路径与下载 URL 解析", () => {
  const coordinate = parseMavenCoordinate("com.flashfin:flp-i18n-tool:0.1.0:cli");
  assert.deepEqual(coordinate, {
    groupId: "com.flashfin",
    artifactId: "flp-i18n-tool",
    version: "0.1.0",
    classifier: "cli",
  });
  assert.equal(parseMavenCoordinate("a:b"), null);
  assert.equal(parseMavenCoordinate("a:b:0.1.0-SNAPSHOT"), null);
  assert.equal(parseMavenCoordinate("../x:b:1.0"), null);
  assert.equal(
    mavenJarUrl("https://repo.example.com/maven/", coordinate),
    "https://repo.example.com/maven/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0-cli.jar",
  );
  assert.equal(
    cacheJarPath("/tmp/cache", coordinate),
    "/tmp/cache/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0-cli.jar",
  );
});

test("从 Maven 下载、校验并复用缓存", async () => {
  const tmpDir = makeTempDir();
  const repo = await startRepo(mavenRoutes());
  const resolver = new CliJarResolver(new Logger("error"));
  try {
    const config = mavenConfig(tmpDir, repo.url);
    const jarPath = await resolver.ensure(config);
    assert.equal(jarPath, config.cliJar);
    assert.deepEqual(fs.readFileSync(jarPath), JAR_BYTES);

    await repo.close();
    const cached = await resolver.ensure(config);
    assert.equal(cached, jarPath);
  } finally {
    await repo.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("校验失败时不写入缓存", async () => {
  const tmpDir = makeTempDir();
  const repo = await startRepo(mavenRoutes({ "/repo/com/example/demo/1.0.0/demo-1.0.0.jar.sha1": "0".repeat(40) }));
  const resolver = new CliJarResolver(new Logger("error"));
  try {
    const config = mavenConfig(tmpDir, repo.url);
    await assert.rejects(
      () => resolver.ensure(config),
      (error) => error.cliCode === "CLI_CHECKSUM_MISMATCH",
    );
    assert.equal(fs.existsSync(config.cliJar), false);
  } finally {
    await repo.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("仓库缺少校验文件时拒绝下载", async () => {
  const tmpDir = makeTempDir();
  const routes = mavenRoutes();
  delete routes["/repo/com/example/demo/1.0.0/demo-1.0.0.jar.sha1"];
  const repo = await startRepo(routes);
  const resolver = new CliJarResolver(new Logger("error"));
  try {
    const config = mavenConfig(tmpDir, repo.url);
    await assert.rejects(
      () => resolver.ensure(config),
      (error) => error.cliCode === "CLI_CHECKSUM_MISSING",
    );
  } finally {
    await repo.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("显式 jar 缺失时报 CLI_UNAVAILABLE", async () => {
  const tmpDir = makeTempDir();
  const resolver = new CliJarResolver(new Logger("error"));
  try {
    const missing = path.join(tmpDir, "missing.jar");
    const config = mavenConfig(tmpDir, "https://example.com/repo", {
      cliJarMode: "path",
      cliJar: missing,
      command: ["java", "-jar", missing],
    });
    await assert.rejects(
      () => resolver.ensure(config),
      (error) => error.cliCode === "CLI_UNAVAILABLE",
    );
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("CliRunner 在 Maven 模式下先下载再执行 CLI", async () => {
  const tmpDir = makeTempDir();
  const repo = await startRepo(mavenRoutes());
  const callLog = path.join(tmpDir, "calls.log");
  const javaWrapper = path.join(tmpDir, "fake-java");
  fs.writeFileSync(
    javaWrapper,
    ["#!/bin/sh", `printf '%s\\n' "$@" >> '${callLog}'`, "shift 2", `exec '${process.execPath}' '${FIXTURE_CLI}' "$@"`, ""].join(
      "\n",
    ),
  );
  fs.chmodSync(javaWrapper, 0o755);
  fs.writeFileSync(path.join(tmpDir, "modules.yaml"), "version: 1\n");

  const config = mavenConfig(tmpDir, repo.url, { command: [javaWrapper, "-jar", ""] });
  config.command[2] = config.cliJar;
  const runner = new CliRunner(new StaticConfigStore(config), new Logger("error"));

  try {
    const result = await runner.run(["status"], 5000);
    assert.equal(result.envelope.ok, true);
    assert.ok(fs.existsSync(config.cliJar));
    const logged = fs.readFileSync(callLog, "utf8");
    assert.match(logged, new RegExp(config.cliJar.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  } finally {
    await repo.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
