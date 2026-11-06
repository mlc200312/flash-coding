"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const test = require("node:test");

const { CONFIG_YAML, makeConfig, parseResult, startServer } = require("./helpers");

test("resources 暴露 modules.yaml 与最新报告", async () => {
  const config = makeConfig();
  const ctx = await startServer(config);
  try {
    const { resources } = await ctx.client.listResources();
    const uris = resources.map((resource) => resource.uri);
    assert.ok(uris.includes("i18n://modules"));
    assert.ok(uris.includes("i18n://reports/latest"));

    const modules = await ctx.client.readResource({ uri: "i18n://modules" });
    assert.equal(modules.contents[0].mimeType, "text/yaml");
    assert.equal(modules.contents[0].text, CONFIG_YAML);
    assert.equal(fs.readFileSync(config.configPath, "utf8"), CONFIG_YAML);

    const empty = await ctx.client.readResource({ uri: "i18n://reports/latest" });
    const emptyPayload = JSON.parse(empty.contents[0].text);
    assert.equal(emptyPayload.empty, true);

    await ctx.client.callTool({ name: "i18n_scan", arguments: { module: "demo" } });

    const latest = await ctx.client.readResource({ uri: "i18n://reports/latest" });
    const latestPayload = JSON.parse(latest.contents[0].text);
    assert.equal(latestPayload.tool, "scan");
    assert.equal(latestPayload.module, "demo");
    assert.equal(latestPayload.result.ok, true);
    assert.ok(latestPayload.generatedAt);
  } finally {
    await ctx.close();
  }
});

test("sync-module prompt 返回安全检查流程", async () => {
  const ctx = await startServer(makeConfig());
  try {
    const { prompts } = await ctx.client.listPrompts();
    assert.ok(prompts.some((prompt) => prompt.name === "sync-module"));

    const prompt = await ctx.client.getPrompt({
      name: "sync-module",
      arguments: { module: "demo", languages: "zh_CN,en" },
    });
    assert.equal(prompt.messages.length, 1);
    const text = prompt.messages[0].content.text;
    assert.match(text, /demo/);
    assert.match(text, /planId/);
    assert.match(text, /dryRun=true/);
    assert.match(text, /绝不跳过 planId/);
    assert.match(text, /只处理语言: zh_CN,en/);

    const scanResult = await ctx.client.callTool({ name: "i18n_scan", arguments: { module: "demo" } });
    assert.equal(parseResult(scanResult).ok, true);
  } finally {
    await ctx.close();
  }
});
