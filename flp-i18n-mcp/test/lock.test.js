"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { ModuleLocks } = require("../dist/lock.js");

test("同一 key 串行执行，不同 key 可并发", async () => {
  const locks = new ModuleLocks();
  const events = [];
  let releaseFirst;

  const first = locks.run("flp-code::demo", async () => {
    events.push("first-start");
    await new Promise((resolve) => {
      releaseFirst = resolve;
    });
    events.push("first-end");
  });
  const second = locks.run("flp-code::demo", async () => {
    events.push("second");
  });
  const other = locks.run("flp-code::other", async () => {
    events.push("other");
  });

  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(events.filter((event) => event !== "other"), ["first-start"]);
  assert.ok(events.includes("other"), "不同 key 应能并发执行");

  releaseFirst();
  await Promise.all([first, second, other]);

  assert.deepEqual(events.filter((event) => event !== "other"), ["first-start", "first-end", "second"]);
  assert.deepEqual(locks.pendingKeys(), []);
});

test("任务失败会释放锁，后续任务继续执行", async () => {
  const locks = new ModuleLocks();
  await assert.rejects(
    locks.run("demo", async () => {
      throw new Error("boom");
    }),
    /boom/,
  );

  const value = await locks.run("demo", async () => "ok");
  assert.equal(value, "ok");
  assert.deepEqual(locks.pendingKeys(), []);
});
