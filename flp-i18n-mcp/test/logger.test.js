"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { Logger, createRedactor } = require("../dist/logger.js");

const TOKEN =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

test("createRedactor 脱敏 Bearer、JWT、token 字段与 Token 原值", () => {
  const redact = createRedactor([TOKEN]);
  const source = [
    `Authorization: Bearer ${TOKEN}`,
    `token=${TOKEN}`,
    'appSecret: "s3cr3t-value"',
    "password=abc123",
    `raw ${TOKEN}`,
  ].join("\n");

  const output = redact(source);

  assert.equal(output.includes(TOKEN), false);
  assert.equal(output.includes("s3cr3t-value"), false);
  assert.equal(output.includes("abc123"), false);
  assert.match(output, /Bearer \[REDACTED\]/);
  assert.match(output, /token=\[REDACTED\]/);
});

test("createRedactor 在不提供 secret 时仍按模式脱敏 JWT 与 Authorization", () => {
  const redact = createRedactor([]);
  const output = redact(`jwt ${TOKEN} Authorization: Bearer abc.def.ghi`);
  assert.equal(output.includes(TOKEN), false);
  assert.match(output, /\[REDACTED_JWT\]/);
  assert.match(output, /Bearer \[REDACTED\]/);
});

test("createRedactor 忽略过短的 secret，避免误伤普通文本", () => {
  const redact = createRedactor(["ab", null, undefined, "  "]);
  assert.equal(redact("abc"), "abc");
});

test("Logger 只写 stderr，并按级别过滤", () => {
  const chunks = [];
  const originalWrite = process.stderr.write;
  process.stderr.write = (chunk) => {
    chunks.push(String(chunk));
    return true;
  };

  try {
    const logger = new Logger("info", createRedactor([]));
    logger.debug("debug-line");
    logger.info("info-line");
    logger.error("error-line", { token: "plain-secret-token" });
  } finally {
    process.stderr.write = originalWrite;
  }

  assert.equal(chunks.length, 2);
  assert.match(chunks[0], /\[flp-i18n-mcp\] INFO info-line/);
  assert.match(chunks[1], /\[flp-i18n-mcp\] ERROR error-line/);
  assert.equal(chunks.join("").includes("debug-line"), false);
  assert.equal(chunks.join("").includes("plain-secret-token"), false);
});
