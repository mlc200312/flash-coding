"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = require("@modelcontextprotocol/sdk/inMemory.js");

const { createServer } = require("../dist/server.js");

const REPO_ROOT = path.resolve(__dirname, "..");
const FIXTURE_CLI = path.join(__dirname, "fixtures", "fake-cli.js");

const CONFIG_YAML = [
  "version: 1",
  "languages: [zh_CN, en]",
  "platform:",
  "  saveUrl: http://127.0.0.1:1/save",
  "  dictionaryBase: http://127.0.0.1:1",
  "modules:",
  "  demo:",
  "    repoRoot: demo",
  "    domainPath: demo/demo-domain",
  "    os: flp-code",
  "    packages: [com.example.demo.type]",
  "    codeFile: DemoCodeMessages",
  "    enumFile: DemoEnumerableMessages",
  "",
].join("\n");

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "flp-i18n-mcp-test-"));
}

function makeConfig(overrides = {}) {
  const tmpDir = overrides.tmpDir || makeTempDir();
  const configPath = path.join(tmpDir, "modules.yaml");
  fs.writeFileSync(configPath, CONFIG_YAML);
  return {
    command: [process.execPath, FIXTURE_CLI],
    configPath,
    allowedRoots: [tmpDir],
    token: "test-token-abc12345",
    logLevel: "error",
    readTimeoutMs: 5000,
    writeTimeoutMs: 10000,
    ...overrides,
    tmpDir,
  };
}

async function startServer(config, overrides = {}) {
  const { server, deps } = createServer(config, overrides);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "flp-i18n-test-client", version: "0.0.0" });

  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

  return {
    server,
    deps,
    client,
    async close() {
      await client.close();
      await server.close();
    },
  };
}

function parseResult(result) {
  const text = result.content.find((item) => item.type === "text");
  if (!text) {
    throw new Error("工具结果缺少 text content");
  }
  return JSON.parse(text.text);
}

async function withEnv(values, fn) {
  const previous = {};
  for (const [key, value] of Object.entries(values)) {
    previous[key] = process.env[key];
    if (value === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = value;
    }
  }
  try {
    return await fn();
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}

function readJsonLines(file) {
  if (!fs.existsSync(file)) {
    return [];
  }
  return fs
    .readFileSync(file, "utf8")
    .split("\n")
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line));
}

module.exports = {
  CONFIG_YAML,
  FIXTURE_CLI,
  REPO_ROOT,
  makeConfig,
  makeTempDir,
  parseResult,
  readJsonLines,
  startServer,
  withEnv,
};
