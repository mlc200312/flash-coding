---
description: Check, upload, or download FLP i18n keys for one module
description_zh: 对指定模块执行多语言检查、上传缺失 key 或下载译文
argument-hint: <module> [os] [check|upload|download]
---

# /i18n-sync

对指定模块执行 FLP 多语言同步。如果 `flp-i18n` Skill 尚未加载，先读取
`${QODER_PLUGIN_ROOT}/skills/flp-i18n/SKILL.md` 并遵守其中的强制流程与禁止事项。

用户输入：$ARGUMENTS

## 参数解析

按位置解析，允许省略 os：

1. 第一个参数：`module`（必填，例如 `flm-flp-base-business`）。
2. 第二个参数：若为 `check`、`upload` 或 `download`，则它是 `mode`，`os` 省略；否则它是 `os`。
3. 第三个参数：`mode`，取值 `check` / `upload` / `download`，默认 `check`。
4. 出现多余参数或无法识别的 mode 时，不要猜测，列出用法并询问用户。

缺少 module 时，先询问模块名；不要自行从仓库目录推断。

## 执行规则

- **check**：调用 `i18n_status`、`i18n_scan`、`i18n_diff`、`i18n_validate`，按 Skill 的报告格式输出。
  全程只读，不调用任何写工具。
- **upload**：先完成 check，再调用 `i18n_upload` 且 `dryRun=true`。展示 planId、条目数和示例 key 后停止，
  明确询问用户是否执行。只有用户明确同意后，才用同一 planId 且 `dryRun=false` 执行，并在结束后汇报结果。
- **download**：先完成 check，再调用 `i18n_download` 且 `dryRun=true`。展示 planId、文件数和文件列表后停止，
  明确询问用户是否执行。只有用户明确同意后，才用同一 planId 且 `dryRun=false` 执行；结束后提示查看
  `git diff`，并给出 `backupDir` 作为回滚位置。

## 约束

- 不打印 Token，不要求用户粘贴 Token。
- 不在未确认时写入，不复用 planId，不绕过 git 检查。
- 遇到 Token 缺失/过期、远端不可用、工作区脏、路径越界时立即停止并说明原因。
- 结束后输出：结论、关键计数、planId（如有）、下一步建议。
