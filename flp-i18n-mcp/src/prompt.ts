import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

const syncModuleArgs = {
  module: z.string().min(1).describe("要同步的模块名"),
  os: z.string().optional().describe("模块 os（同名模块跨 os 时使用）"),
  languages: z.string().optional().describe("逗号分隔的语言代码，例如 zh_CN,en；省略则使用全部语言"),
};

export function registerPrompts(server: McpServer): void {
  server.registerPrompt(
    "sync-module",
    {
      title: "同步单个模块的多语言",
      description: "按安全检查顺序完成一个模块的状态检查、扫描、差异分析、dry-run 与确认后的写入",
      argsSchema: syncModuleArgs,
    },
    ({ module, os, languages }) => {
      const target = os ? `${module}（os=${os}）` : module;
      const languageHint = languages ? `只处理语言: ${languages}` : "使用配置中的全部语言";
      const text = [
        `请对模块 ${target} 执行 FLP 多语言同步，严格按以下顺序操作：`,
        "",
        "1. 调用 i18n_status，确认 configOk=true、Token 有效、远端可达。若 Token 缺失/过期或远端不可达，停止并报告，不要尝试写入。",
        `2. 调用 i18n_scan（module=${module}${os ? `, os=${os}` : ""}），检查 key 数量、重复与类加载失败。`,
        `3. 调用 i18n_diff（module=${module}${os ? `, os=${os}` : ""}），查看 missingOnPlatform / missingLocally / zhConflicts / remoteUnavailable。${languageHint}。`,
        "4. 调用 i18n_validate 检查本地文件问题；有 error 时先修复或报告，不要盲目写入。",
        "5. 上传前先以 dryRun=true 调用 i18n_upload，保存返回的 planId，并向用户展示：影响模块、条目数、示例 key、limit/rate-ms。",
        "6. 只有用户明确同意后，才用完全相同的 planId 和 dryRun=false 再次调用 i18n_upload。",
        "7. 下载前同理：先 dryRun=true 调用 i18n_download 展示将修改的文件与操作数，取得用户同意后再以同一 planId + dryRun=false 执行；执行前确认工作区干净，执行后检查 git diff 与备份目录。",
        "",
        "安全规则：",
        "- 绝不打印、回显或写入 Token；工具输出中如出现疑似密钥，立即停止并报告。",
        "- 绝不跳过 planId、绝不复用其他模块或其他改动的 planId、绝不使用 --force 绕过 git 检查。",
        "- 遇到 GIT_DIRTY、PLAN_STALE、REMOTE_UNAVAILABLE、TOKEN_MISSING/EXPIRED 时停止，不要自动重试写入。",
        "- 写入失败时报告 journal/已完成项，不要重复提交已成功的条目。",
      ].join("\n");

      return {
        messages: [{ role: "user", content: { type: "text", text } }],
      };
    },
  );
}
